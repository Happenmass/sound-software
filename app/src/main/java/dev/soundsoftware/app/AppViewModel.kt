package dev.soundsoftware.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.RemoteAsrSettings
import dev.soundsoftware.app.export.Exporter
import dev.soundsoftware.app.inference.AndroidMediaAudioDecoder
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.export.ExportFormat
import dev.soundsoftware.core.export.ExportOptions
import dev.soundsoftware.core.model.*
import dev.soundsoftware.core.transcript.silentGaps
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface Screen {
    data object Main : Screen
    data class Sync(val selecting: Boolean = false) : Screen
    /** null id edits the defaults used for auto-transcription after sync. */
    data class Setup(val recordingId: String?) : Screen
    data class Detail(val id: String) : Screen
    data class Speakers(val id: String, val speakerId: String? = null) : Screen
    data class Export(val id: String) : Screen
    data object Device : Screen
    data object Search : Screen
    data object Assistant : Screen
    data object AsrService : Screen
    data object LlmService : Screen
}

enum class Tab { RECORDINGS, TODOS, DEVICE, ME }

/** UI coordinator. Durable work lives in [SoundApplication] so it survives this screen. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SoundApplication
    val recordings = app.repository.recordings
    val device = app.devices.state
    val settings = app.settings.state
    val playback = app.playback.state
    private val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Main))
    val screens = stack.asStateFlow()
    private val mutableTab = MutableStateFlow(Tab.RECORDINGS)
    val tab = mutableTab.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableAsking = MutableStateFlow(false)
    val asking = mutableAsking.asStateFlow()
    fun assistantState(recordingId: String?) = app.assistant.stateFor(recordingId)
    fun askAssistant(question: String, recordingId: String?) = app.assistant.ask(question, recordingId)
    fun cancelAssistant(recordingId: String?, runId: String?) = app.assistant.cancel(recordingId, runId)
    fun clearAssistantHistory(recordingId: String?) = app.assistant.clearHistory(recordingId)
    fun openCitation(ref: dev.soundsoftware.core.agent.RecordingCitation) = viewModelScope.launch {
        val valid = app.recordingIndex.resolve(ref)
        val r = app.repository.get(ref.recordingId)
        if (valid == null || r == null) say(app.getString(R.string.recording_changed))
        else { push(Screen.Detail(r.id)); valid.startMs?.let { playFrom(r, it) } }
    }

    fun push(screen: Screen) = stack.update { it + screen }
    fun back(): Boolean { if (stack.value.size <= 1) return false; stack.update { it.dropLast(1) }; return true }
    fun replaceTop(screen: Screen) = stack.update { it.dropLast(1) + screen }
    fun selectTab(tab: Tab) { mutableTab.value = tab; stack.value = listOf(Screen.Main) }
    fun say(text: String?) { mutableMessage.value = text }
    fun openRecording(id: String) { if (app.repository.get(id) != null) { stack.value = listOf(Screen.Main, Screen.Detail(id)) } }
    fun showSync() { stack.value = listOf(Screen.Main, Screen.Sync()) }

    fun title(r: Recording) = app.displayTitle(r)
    fun qwenHtpUnavailableReason() = app.processor.qwenHtpUnavailableReason()
    fun localAsrProfiles() = app.processor.localAsrProfiles()
    fun modelReady(profileId: String) = app.processor.modelReady(profileId)
    fun llmReady() = app.processor.llmReady()
    fun diarizationReady() = app.processor.diarizer.isReady
    fun estimateMs(r: Recording?, options: TranscribeOptions) = app.processor.estimateMs(r?.durationMs ?: 0, options)
    fun newRecordings() = app.devices.newRecordings(device.value, recordings.value)

    // ---- Device ----
    fun permissionsGranted(granted: Boolean) = app.setBluetoothAllowed(granted)
    fun foreground(value: Boolean) = app.setActivityVisible(value)
    fun scan() = app.devices.scan(userInitiated = true)
    fun bindNearby() = app.devices.bindNearby()
    fun dismissDiscovery() = app.devices.dismissDiscovery()
    fun reconnect() = app.devices.ensureConnected()
    fun refreshDevice() = app.devices.refresh()
    fun toggleDeviceRecording() = app.devices.toggleRecording()
    fun setFindMy(value: Boolean) = app.devices.setFindMy(value)
    fun deleteFromDevice(fileId: Long) = app.devices.deleteFromDevice(fileId)
    fun unbind() { app.devices.unbind(); selectTab(Tab.RECORDINGS) }
    fun factoryReset() { app.devices.factoryReset(); selectTab(Tab.RECORDINGS) }
    fun clearDeviceError() = app.devices.clearError()
    var syncTransport by androidx.compose.runtime.mutableStateOf(dev.soundsoftware.app.device.SyncTransport.BLUETOOTH)
    fun startSync(fileIds: Set<Long>? = null, transport: dev.soundsoftware.app.device.SyncTransport = syncTransport) = app.devices.sync(fileIds, transport)
    fun cancelSync() = app.devices.cancelSync()
    fun readHardwareHighlights(id: String,transport: dev.soundsoftware.app.device.SyncTransport) = app.devices.readHardwareHighlights(id,transport)
    fun setAutoTranscribe(value: Boolean) = app.settings.update { it.copy(autoTranscribe = value) }
    fun setAutoSync(value: Boolean) = app.settings.update { it.copy(autoSync = value) }
    fun setDeleteAfterSync(value: Boolean) = app.settings.update { it.copy(deleteAfterSync = value) }

    // ---- Processing ----
    fun saveDefaults(options: TranscribeOptions) = app.settings.update { it.copy(defaults = options.withCurrentAsrProfile()) }
    fun startTranscription(id: String, options: TranscribeOptions) = viewModelScope.launch {
        app.settings.update { it.copy(defaults = options.copy(template = it.defaults.template)) }
        app.processor.enqueue(id, options)
    }
    fun recalculateSpeakers(id: String, count: Int?) = app.processor.rediarize(id,count)
    fun cancelTranscription(id: String) = app.processor.cancel(id)
    fun resummarize(id: String, template: SceneTemplate) = app.processor.resummarize(id, template)
    fun ask(id: String, question: String) = viewModelScope.launch {
        mutableAsking.value = true
        try { app.processor.ask(id, question).onFailure { say(it.message ?: app.getString(R.string.qa_failed)) } }
        finally { mutableAsking.value = false }
    }

    // ---- Recording edits ----
    private fun edit(id: String, transform: (Recording) -> Recording) = viewModelScope.launch { app.repository.update(id, transform) }
    fun rename(id: String, title: String) = edit(id) { it.copy(title = title.trim().ifEmpty { null }, titleEditedByUser = title.isNotBlank()) }
    fun toggleTodo(id: String, todoId: String) = edit(id) { r ->
        r.copy(summary = r.summary?.let { s -> s.copy(todos = s.todos.map { if (it.id == todoId) it.copy(done = !it.done) else it }) })
    }
    fun deleteTodo(id: String, todoId: String) = edit(id) {it.withoutTodo(todoId)}
    fun editSegment(id: String, segmentId: String, text: String) = edit(id) { r ->
        r.copy(transcript = r.transcript?.let { t -> t.copy(segments = t.segments.map { if (it.id == segmentId) it.copy(text = text, edited = true) else it }) })
    }
    fun nameSpeaker(id: String, speakerId: String, name: String) {
        val clean = name.trim()
        edit(id) { r -> r.copy(speakers = r.speakers.map { if (it.id == speakerId) it.copy(name = clean.ifEmpty { null }) else it }) }
        if (clean.isNotEmpty()) app.settings.update { it.copy(recentSpeakerNames = (listOf(clean) + it.recentSpeakerNames).distinct().take(8)) }
    }
    fun mergeSpeaker(id: String, from: String, into: String) = edit(id) { r ->
        r.copy(speakers = r.speakers.filterNot { it.id == from },
            transcript = r.transcript?.let { t -> t.copy(segments = t.segments.map { if (it.speakerId == from) it.copy(speakerId = into) else it }) })
    }
    fun deleteRecording(id: String) = viewModelScope.launch {
        if (playback.value.recordingId == id) app.playback.release()
        app.processor.deleteRecording(id)
        app.assistant.recordingDeleted(id)
        stack.value = listOf(Screen.Main)
    }

    // ---- Playback ----
    fun prepare(r: Recording) = runCatching { app.playback.load(r.id, app.repository.audioFile(r.id), silentGaps(r.transcript?.segments.orEmpty(), r.durationMs)) }
    fun playPause(r: Recording) { prepare(r); app.playback.toggle() }
    fun playFrom(r: Recording, ms: Long) { prepare(r); app.playback.play(ms) }
    fun seekBy(delta: Long) = app.playback.seekBy(delta)
    fun seekTo(ms: Long) = app.playback.seekTo(ms)
    fun cycleSpeed() = app.playback.cycleSpeed()
    fun toggleSkipSilence() = app.playback.setSkipSilence(!playback.value.skipSilence)

    // ---- Services ----
    fun saveAsr(remote: RemoteAsrSettings, key: String?) = runCatching {
        if (remote.endpoint.isNotBlank()) {
            val url = remote.endpoint.trim().toHttpUrl(); require(url.isHttps) { app.getString(R.string.asr_https_required) }
            require(remote.model.isNotBlank()) { app.getString(R.string.enter_model_name) }
        }
        app.settings.update { it.copy(remoteAsr = remote.copy(endpoint = remote.endpoint.trim(), model = remote.model.trim())) }
        if (key != null) app.settings.secrets.put(Processor.KEY_ASR, key.trim())
    }.onFailure { say(it.message) }.isSuccess
    fun saveLlm(llm: LlmSettings, key: String?) = runCatching {
        if (llm.baseUrl.isNotBlank()) {
            val url = llm.baseUrl.trim().toHttpUrl(); require(url.isHttps || Processor.debugLoopback(url)) { app.getString(R.string.llm_https_required) }
            require(llm.model.isNotBlank()) { app.getString(R.string.enter_model_name) }
        }
        app.settings.update { it.copy(llm = llm.copy(baseUrl = llm.baseUrl.trim(), model = llm.model.trim())) }
        if (key != null) app.settings.secrets.put(Processor.KEY_LLM, key.trim())
    }.onFailure { say(it.message) }.isSuccess
    fun hasSecret(name: String) = app.settings.secrets.has(name)

    fun importAudio(uri: Uri) = viewModelScope.launch {
        val id = UUID.randomUUID().toString()
        val target = app.repository.audioFile(id)
        try {
            say(app.getString(R.string.importing))
            val recording = withContext(Dispatchers.IO) {
                val encoded = File.createTempFile("import-", ".audio", app.cacheDir)
                try {
                    app.contentResolver.openInputStream(uri)?.use { input -> encoded.outputStream().use { output ->
                        val buf = ByteArray(65536); var total = 0L
                        while (true) { ensureActive(); val n = input.read(buf); if (n < 0) break
                            total += n; require(total <= 1024L * 1024 * 1024) { app.getString(R.string.import_size_limit) }; output.write(buf, 0, n) }
                    } } ?: error(app.getString(R.string.cannot_read_selected_file))
                    val context = currentCoroutineContext()
                    AndroidMediaAudioDecoder { context.ensureActive() }.decodeToCanonicalWav(encoded, target)
                    val wav = PcmWav.open(target)
                    Recording(id, System.currentTimeMillis(), wav.durationMs, RecordingSource.IMPORT, audioBytes = target.length(),
                        waveform = RecordingRepository.waveform(target))
                } finally { encoded.delete() }
            }
            app.repository.add(recording)
            say(null)
            push(Screen.Setup(id))
        } catch (e: CancellationException) { target.delete(); throw e
        } catch (e: Exception) { target.delete(); say(app.getString(R.string.import_failed, e.message)) }
    }

    fun export(r: Recording, format: ExportFormat, options: ExportOptions, onReady: (File, String) -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { Exporter.write(app, r, title(r), format, options) } }
            .onSuccess { onReady(it, format.mime) }.onFailure { say(app.getString(R.string.export_failed, it.message)) }
    }

    fun exportAudio(r: Recording, onReady: (File, String) -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { Exporter.writeAudio(app,title(r),app.repository.audioFile(r.id)) } }
            .onSuccess { onReady(it,"audio/wav") }.onFailure { say(app.getString(R.string.audio_export_failed, it.message)) }
    }

    fun copyText(r: Recording, options: ExportOptions) {
        val text = Exporter.plainText(app, r, title(r), options)
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title(r), text))
        say(app.getString(R.string.copied))
    }
}
