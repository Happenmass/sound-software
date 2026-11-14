package dev.soundsoftware.app.processing

import dev.soundsoftware.app.R
import dev.soundsoftware.app.inference.QwenHtpFiles
import dev.soundsoftware.app.inference.QwenHtpRuntime
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.inference.SherpaDiarizer
import dev.soundsoftware.core.analysis.LlmConfig
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.analysis.FileAnalysisCheckpoints
import dev.soundsoftware.core.analysis.SummaryCommit
import dev.soundsoftware.core.asr.*
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import dev.soundsoftware.core.transcript.SpeakerTurn
import dev.soundsoftware.core.transcript.assignSpeakers
import dev.soundsoftware.core.transcript.mergeTurns
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

data class AsrProfile(val id: String, val titleRes: Int, val subtitleRes: Int, val local: Boolean)

val ASR_PROFILES = listOf(
    AsrProfile("qwen-htp", R.string.asr_qwen_htp, R.string.asr_qwen_htp_description, true),
    AsrProfile("http-api", R.string.asr_http, R.string.asr_http_description, false),
)

/**
 * Sequential job queue: diarization → recognition → summary. Each step persists its result, so a
 * failed summary never discards a finished transcript, and process death resumes from checkpoints.
 */
class Processor(
    private val repository: RecordingRepository,
    private val settings: SettingsStore,
    private val modelDir: File,
    private val checkpointDir: File,
    private val scope: CoroutineScope,
    val diarizer: dev.soundsoftware.app.inference.SpeakerAnalyzer = SherpaDiarizer(File(modelDir, "diarization")),
    private val analysisClient: OkHttpClient = OkHttpClient(),
    private val qwenHtpFiles: QwenHtpFiles? = null,
    private val notify: (Recording) -> Unit,
) {
    private data class QueuedRecording(val id: String, val generation: Long)
    private val queue = Channel<QueuedRecording>(Channel.UNLIMITED)
    private val generations = ConcurrentHashMap<String, Long>()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    @Volatile private var current: Pair<String, Job>? = null
    private val summaryRequests = ConcurrentHashMap<String, String>()
    private val manualSummaryJobs = ConcurrentHashMap<String, Job>()
    private val answerJobs = ConcurrentHashMap<String, MutableSet<Job>>()
    private val discardedAnalysisIds = ConcurrentHashMap.newKeySet<String>()
    private val diarizationExecution = kotlinx.coroutines.sync.Mutex()
    private val speakerJobs = ConcurrentHashMap<String, Job>()
    private fun refreshBusy() { mutableBusy.value = current != null || summaryRequests.isNotEmpty() || speakerJobs.values.any { it.isActive } }

    fun start() {
        scope.launch {
            repository.recordings.value.filter { it.stage == ProcessingStage.QUEUED }
                .forEach { queue.send(QueuedRecording(it.id, generations[it.id] ?: 0)) }
            for (entry in queue) {
                val id = entry.id
                if (id in discardedAnalysisIds || entry.generation != (generations[id] ?: 0L)) continue
                mutableBusy.value = true
                val job = scope.launch { process(id) }
                current = id to job
                job.join()
                current = null
                mutableBusy.value = !queue.isEmpty || summaryRequests.isNotEmpty() || speakerJobs.values.any { it.isActive }
            }
        }
    }

    fun qwenHtpUnavailableReason(): String? =
        if (qwenHtpFiles == null) settings.text(R.string.qwen_htp_not_bundled) else qwenHtpFiles.unavailable()

    fun localAsrProfiles(): List<AsrProfile> =
        ASR_PROFILES.filter { it.local && qwenHtpFiles?.supported() == true }

    fun modelReady(profileId: String): Boolean = when (profileId) {
        "qwen-htp" -> qwenHtpUnavailableReason() == null
        "http-api" -> settings.state.value.remoteAsr.let { it.endpoint.isNotBlank() && it.model.isNotBlank() } && settings.secrets.has(KEY_ASR)
        else -> false
    }

    fun llmReady(): Boolean = settings.state.value.llm.let { it.baseUrl.isNotBlank() && it.model.isNotBlank() }

    /** Rough planning estimate shown before starting; not a measured benchmark. */
    fun estimateMs(durationMs: Long, options: TranscribeOptions): Long {
        val rtf = 0.15
        val diarize = if (diarizer.isReady) 0.08 else 0.0
        val summary = if (options.template != SceneTemplate.TRANSCRIPT_ONLY && llmReady()) 30_000 else 0
        return (durationMs * (rtf + diarize)).toLong() + summary + 5_000
    }

    suspend fun enqueue(id: String, options: TranscribeOptions) {
        if (id in discardedAnalysisIds) return
        val generation = generations[id] ?: 0L
        speakerJobs[id]?.cancelAndJoin()
        manualSummaryJobs[id]?.cancelAndJoin()
        if (id in discardedAnalysisIds) return
        summaryRequests.remove(id)
        repository.update(id) { it.copy(stage = ProcessingStage.QUEUED, options = options.withCurrentAsrProfile(), progress = null, stageText = settings.text(R.string.queued), error = null) }
        if (id !in discardedAnalysisIds && generation == (generations[id] ?: 0L))
            queue.send(QueuedRecording(id, generation))
    }

    fun cancel(id: String) {
        current?.takeIf { it.first == id }?.second?.cancel()
        speakerJobs[id]?.cancel()
        manualSummaryJobs[id]?.cancel()
        summaryRequests.remove(id)
        scope.launch { repository.update(id) { if (it.stage != ProcessingStage.DONE)
            it.copy(stage = if (it.transcript != null) ProcessingStage.DONE else ProcessingStage.NEW, progress = null, stageText = "已取消") else it } }
    }

    private fun progress(id: String, stage: ProcessingStage, fraction: Float?, text: String) =
        repository.updateTransient(id) { it.copy(stage = stage, progress = fraction, stageText = text) }

    private suspend fun process(id: String) {
        val recording = repository.get(id) ?: return
        val options = (recording.options ?: settings.state.value.defaults).withCurrentAsrProfile()
        try {
            if (!modelReady(options.profileId)) throw AsrException(ErrorCode.MODEL_NOT_READY,
                when(options.profileId) {"http-api" -> settings.text(R.string.cloud_asr_not_configured);"qwen-htp" -> qwenHtpUnavailableReason() ?: settings.text(R.string.unavailable);else -> settings.text(R.string.local_asr_models_missing)})
            val wav = withContext(Dispatchers.IO) { PcmWav.open(repository.audioFile(id)) }
            repository.update(id) { it.copy(stage = ProcessingStage.TRANSCRIBING, stageText = settings.text(R.string.preparing), error = null) }
            val warnings = mutableListOf<String>()
            val llm = settings.state.value.llm
            val repair = if (llm.repairTranscript && llm.baseUrl.isNotBlank() && llm.model.isNotBlank()) {
                runCatching { analyzer(llm = llm) }.getOrNull()
            } else null
            var turns: List<SpeakerTurn>? = null
            var speakerHtpExecutions = 0
            if (diarizer.isReady && wav.durationMs <= SherpaDiarizer.MAX_DURATION_MS) {
                progress(id, ProcessingStage.DIARIZING, 0f, settings.text(R.string.analyzing_speakers))
                turns = runCatching {
                    withContext(Dispatchers.Default) { runDiarization(wav, options.speakerCount) { f -> progress(id, ProcessingStage.DIARIZING, f * 0.25f, settings.text(R.string.analyzing_speakers)) } }.also {
                        warnings += it.warnings
                        speakerHtpExecutions = if (it.turns.isEmpty()) 0 else it.npu?.htpExecutions ?: 0
                    }.turns
                }.onFailure { if (it is CancellationException) throw it; warnings += settings.text(R.string.speaker_analysis_failed, it.message) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            } else if (!diarizer.isReady) warnings += settings.text(R.string.speaker_models_missing)
            val base = if (turns != null) 0.25f else 0f
            progress(id, ProcessingStage.TRANSCRIBING, base, settings.text(R.string.transcribing))
            val result = recognize(id, options, turns, base)
            val repairCount = if (repair == null) 0 else result.segments.count { it.speaker != null && it.fragments.size > 1 }
            var repaired = 0
            var repairFailed = false
            val recognized = result.segments.map { segment ->
                var text = segment.text
                if (repair != null && !repairFailed && segment.speaker != null && segment.fragments.size > 1) {
                    repaired++
                    progress(id, ProcessingStage.TRANSCRIBING, null, settings.text(R.string.repairing_transcript, repaired, repairCount))
                    try {
                        val corrected = withTimeoutOrNull(45_000) { repair.repairTranscript(segment.fragments) }
                        if (corrected == null) repairFailed = true else text = corrected
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        repairFailed = true
                    }
                }
                Segment(segment.id, text, segment.startMs, segment.endMs, segment.speaker)
            }
            if (repairFailed) warnings.add(0, settings.text(R.string.repair_transcript_failed))
            val (segments, speakers) = if (options.profileId == "http-api" && turns != null && result.timestampQuality != TimestampQuality.NONE) {
                assignSpeakers(recognized, mergeTurns(turns))
            } else {
                recognized to recognized.mapNotNull { it.speakerId }.distinct().mapIndexed { index, id -> Speaker(id, index + 1) }
            }
            val transcript = Transcript(segments, result.provenance.model, result.provenance.backend, result.timestampQuality.name,
                System.currentTimeMillis(), warnings + result.warnings, speakerHtpExecutions)
            val wantsSummary = options.template != SceneTemplate.TRANSCRIPT_ONLY && llmReady()
            val saved = repository.update(id) { it.copy(transcript = transcript, speakers = speakers, summary = null, qa = emptyList(),
                stage = if (wantsSummary) ProcessingStage.SUMMARIZING else ProcessingStage.DONE,
                progress = null, stageText = if (wantsSummary) settings.text(R.string.generate_notes) else null) } ?: return
            if (wantsSummary) summarizeNow(saved, options)
            repository.get(id)?.let(notify)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.update(id) { it.copy(stage = if (it.transcript != null) ProcessingStage.DONE else ProcessingStage.NEW,
                progress = null, stageText = "已取消") } }
            throw e
        } catch (e: Throwable) {
            repository.update(id) { it.copy(stage = ProcessingStage.FAILED, progress = null, stageText = null, error = e.message ?: settings.text(R.string.transcription_failed)) }
        }
    }

    private suspend fun recognize(id: String, options: TranscribeOptions, turns: List<SpeakerTurn>?, base: Float): AsrResult {
        val audio = withContext(Dispatchers.IO) { AudioInput.fromWav(repository.audioFile(id), id) }
        val engine: AsrEngine = when (options.profileId) {
            "http-api" -> {
                val remote = settings.state.value.remoteAsr
                HttpFileAsrAdapter(HttpAsrConfig(remote.endpoint.toHttpUrl(), remote.model, supportsSegmentTimestamps = remote.segmentTimestamps), { settings.secrets.get(KEY_ASR) })
            }
            "qwen-htp" -> {
                val files = qwenHtpFiles ?: throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, settings.text(R.string.qwen_htp_not_bundled))
                val runtime = QwenHtpRuntime(files, File(checkpointDir, "qwen-htp-windows"))
                LocalAsrAdapter(runtime, speakerTurns = turns.orEmpty())
            }
            else -> throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, settings.text(R.string.unknown_asr))
        }
        try {
            val revision = listOf(options.profileId, options.effectiveLanguage, options.hotwords.joinToString(","), settings.state.value.remoteAsr.model, "v2").joinToString("|")
            return engine.transcribe(AsrRequest(id, UUID.randomUUID().toString(), audio, revision),
                AsrExecutionContext(FileCheckpointStore(checkpointDir)) { event ->
                    val f = event.fraction?.toFloat()
                    progress(id, ProcessingStage.TRANSCRIBING, f?.let { base + it * (1 - base) },
                        when (event.stage) { AsrStage.UPLOADING -> settings.text(R.string.uploading); AsrStage.FINALIZING -> settings.text(R.string.finalizing); else -> settings.text(R.string.transcribing) })
                })
        } finally { withContext(NonCancellable + Dispatchers.IO) { engine.close() } }
    }

    private suspend fun runDiarization(wav: PcmWav, count: Int?, progress: (Float)->Unit): dev.soundsoftware.app.inference.DiarizationAnalysis {
        diarizationExecution.lock()
        try { return diarizer.analyze(wav,count,progress) } finally { diarizationExecution.unlock() }
    }

    private fun analysisDirectory(id: String) = File(checkpointDir, "analysis/${UUID.nameUUIDFromBytes(id.toByteArray())}")

    private fun analyzer(recordingId: String? = null, llm: LlmSettings = settings.state.value.llm): MeetingAnalyzer {
        val url = llm.baseUrl.trim().trimEnd('/').plus("/").toHttpUrl()
        val key = settings.secrets.get(KEY_LLM) // Keep credentials paired with this generation's endpoint/model snapshot.
        return MeetingAnalyzer(LlmConfig(url, llm.model.trim(), allowInsecureForTests = debugLoopback(url), outputLanguage = settings.outputLanguage()), { key }, client = analysisClient,
            checkpoints = recordingId?.let { FileAnalysisCheckpoints(analysisDirectory(it)) } ?: dev.soundsoftware.core.analysis.NoAnalysisCheckpoints)
    }

    private suspend fun summarizeNow(recording: Recording, options: TranscribeOptions) {
        if (recording.id in discardedAnalysisIds) return
        val token = UUID.randomUUID().toString()
        summaryRequests[recording.id] = token
        try {
            val summary = analyzer(recording.id).summarize(recording, options.template, options.hotwords) { text ->
                if (summaryRequests[recording.id] == token) progress(recording.id, ProcessingStage.SUMMARIZING, null, text)
            }
            if (summaryRequests[recording.id] != token) return
            repository.update(recording.id) { r ->
                val accepted = SummaryCommit.reconcile(recording, r, summary)
                r.copy(summary = accepted, stage = ProcessingStage.DONE, stageText = null, error = null,
                    title = if (r.titleEditedByUser) r.title else accepted.title ?: r.title)
            }
            // Successful generations are not permanent memoization: explicit regenerate must be fresh.
            if (summaryRequests[recording.id] == token) withContext(Dispatchers.IO) { analysisDirectory(recording.id).deleteRecursively() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (summaryRequests[recording.id] == token)
                repository.update(recording.id) { it.copy(stage = ProcessingStage.DONE, stageText = null, error = settings.text(R.string.notes_failed, e.message)) }
        } finally { summaryRequests.remove(recording.id, token) }
    }

    /** Recompute identity locally; no ASR/HTTP call and no partial rewrite on cancellation/failure. */
    fun rediarize(id: String, count: Int?): Job {
        require(count == null || count in 1..12)
        var previous: Job? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ownsStage = false
            try {
                previous?.cancelAndJoin()
                manualSummaryJobs[id]?.cancelAndJoin()
                if (id in discardedAnalysisIds) return@launch
                val base = repository.get(id) ?: return@launch
                check(base.transcript != null) { settings.text(R.string.transcript_required) }
                check(base.stage !in listOf(ProcessingStage.QUEUED, ProcessingStage.TRANSCRIBING, ProcessingStage.DIARIZING, ProcessingStage.SUMMARIZING)) { settings.text(R.string.wait_for_processing) }
                check(diarizer.isReady) { settings.text(R.string.speaker_model_missing) }
                mutableBusy.value = true
                ownsStage = true
                repository.update(id) { it.copy(stage=ProcessingStage.DIARIZING,stageText=settings.text(R.string.recalculate_speakers),progress=0f,error=null) }
                val audio=repository.audioFile(id)
                val before=withContext(Dispatchers.IO) { dev.soundsoftware.core.asr.AudioInput.fromWav(audio,id).sha256 }
                val result=withContext(Dispatchers.Default) {
                    runDiarization(PcmWav.open(audio),count) { f->progress(id,ProcessingStage.DIARIZING,f,settings.text(R.string.recalculate_speakers)) }
                }
                ensureActive()
                check(result.turns.isNotEmpty()) { settings.text(R.string.no_speech_to_analyze) }
                check(id !in discardedAnalysisIds) { settings.text(R.string.recording_deleting) }
                val after=withContext(Dispatchers.IO) { dev.soundsoftware.core.asr.AudioInput.fromWav(audio,id).sha256 }
                check(before==after) { settings.text(R.string.audio_changed) }
                repository.update(id) { current ->
                    dev.soundsoftware.core.transcript.SpeakerReanalysis.commit(base,current,result.turns,result.warnings,count,System.currentTimeMillis(),
                        speakerHtpExecutions = result.npu?.htpExecutions ?: 0)
                        .copy(stage=ProcessingStage.DONE,stageText=null,progress=null,error=null)
                }
            } catch(e: CancellationException) {
                if (ownsStage) withContext(NonCancellable) { repository.update(id) { if(it.stage==ProcessingStage.DIARIZING)it.copy(stage=ProcessingStage.DONE,stageText="已取消",progress=null) else it } }
                throw e
            } catch(e: Exception) {
                repository.update(id) { it.copy(stage=if(ownsStage && it.transcript!=null)ProcessingStage.DONE else it.stage,
                    progress=if(ownsStage)null else it.progress,stageText=if(ownsStage)null else it.stageText,error=settings.text(R.string.speaker_reanalysis_failed, e.message)) }
            } finally {
                withContext(NonCancellable) { previous?.join() }
            }
        }
        previous=speakerJobs.put(id,job); previous?.cancel()
        job.invokeOnCompletion { speakerJobs.remove(id,job); refreshBusy() }
        job.start()
        return job
    }

    /** Re-runs only the summary on the current (possibly edited) transcript. */
    fun resummarize(id: String, template: SceneTemplate): Job {
        var previous: Job? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (id in discardedAnalysisIds) return@launch
                previous?.cancelAndJoin()
                speakerJobs[id]?.join()
                current?.takeIf { it.first == id }?.second?.join()
                if (id in discardedAnalysisIds) return@launch
                val base = repository.get(id) ?: return@launch
                if (base.summary != null && base.error == null && base.stageText != "已取消")
                    withContext(Dispatchers.IO) { analysisDirectory(id).deleteRecursively() }
                val recording = repository.update(id) { it.copy(stage = ProcessingStage.SUMMARIZING, stageText = settings.text(R.string.generate_notes), error = null,
                    options = (it.options ?: settings.state.value.defaults).copy(template = template)) } ?: return@launch
                mutableBusy.value = true
                summarizeNow(recording, recording.options!!)
            } finally {
                // The latest job owns its cancelled predecessors too; joining it drains their IO.
                withContext(NonCancellable) { previous?.join() }
                refreshBusy()
            }
        }
        previous = manualSummaryJobs.put(id, job)
        previous?.cancel()
        job.invokeOnCompletion { manualSummaryJobs.remove(id, job) }
        job.start()
        return job
    }

    suspend fun ask(id: String, question: String): Result<Unit> {
        val job = currentCoroutineContext().job
        val jobs = answerJobs.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
        jobs += job
        return try {
            check(id !in discardedAnalysisIds) { settings.text(R.string.recording_deleting) }
            val recording = repository.get(id) ?: error(settings.text(R.string.recording_missing))
            val answer = analyzer().answerGrounded(recording, question, recording.qa)
            repository.update(id) {
                SummaryCommit.requireTranscript(it, answer.sourceTranscriptHash)
                it.copy(qa = it.qa + QaTurn(question, answer.text, System.currentTimeMillis(), answer.evidence, answer.sourceTranscriptHash))
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        finally {
            jobs -= job
            if (jobs.isEmpty()) answerJobs.remove(id, jobs)
        }
    }

    /** Hold the admission guard through deletion; invalidate queued work without banning a later re-sync. */
    suspend fun deleteRecording(id: String) {
        if (!discardedAnalysisIds.add(id)) return
        generations.merge(id, 1L) { previous, one -> previous + one }
        try {
            summaryRequests.remove(id)
            val jobs = (listOfNotNull(current?.takeIf { it.first == id }?.second, manualSummaryJobs.remove(id), speakerJobs.remove(id)) + answerJobs.remove(id).orEmpty()).distinct()
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            withContext(Dispatchers.IO) { analysisDirectory(id).deleteRecursively() }
            repository.delete(id)
        } finally { discardedAnalysisIds.remove(id) }
    }

    companion object {
        /** Debug builds may talk to a mock service on the test machine via `adb reverse`. */
        fun debugLoopback(url: okhttp3.HttpUrl) = dev.soundsoftware.app.BuildConfig.DEBUG && url.host == "localhost"
        const val KEY_ASR = "asr-api-key"; const val KEY_LLM = "llm-api-key" }
}
