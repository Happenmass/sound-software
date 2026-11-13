package dev.soundsoftware.app.data

import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Recordings are `audio/<id>.wav` plus `audio/<id>.meta.json`, written atomically. */
class RecordingRepository(filesDir: File, private val projection: dev.soundsoftware.app.agent.RecordingProjection? = null) {
    val audioDir = File(filesDir, "audio").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<List<Recording>>(emptyList())
    val recordings = mutable.asStateFlow()
    private val writes = Mutex()

    fun audioFile(id: String) = File(audioDir, "$id.wav")
    fun get(id: String) = mutable.value.firstOrNull { it.id == id }

    suspend fun load() = withContext(Dispatchers.IO) {
        val items = audioDir.listFiles { f -> f.extension == "wav" }.orEmpty().mapNotNull { wav ->
            val id = wav.nameWithoutExtension
            val meta = File(audioDir, "$id.meta.json")
            runCatching { json.decodeFromString<Recording>(meta.readText()) }.getOrNull()?.let { saved ->
                // A job interrupted by process death (possibly a native crash or OOM) is not restarted
                // automatically, which would loop; the user retries and finished windows are reused.
                if (saved.stage in listOf(ProcessingStage.DIARIZING, ProcessingStage.TRANSCRIBING, ProcessingStage.SUMMARIZING))
                    saved.copy(stage = if (saved.transcript != null) ProcessingStage.DONE else ProcessingStage.FAILED, progress = null, stageText = null,
                        error = "上次处理被中断（应用被关闭或内存不足），可重新开始；已完成的片段会复用") else saved
            } ?: runCatching { migrateLegacy(wav) }.getOrNull()
        }
        projection?.reconcile(items)
        mutable.value = items.sortedByDescending { it.createdAt }
        items.forEach { persist(it) }
    }

    /** Converts files written by the earlier development build. */
    private fun migrateLegacy(wav: File): Recording {
        val pcm = PcmWav.open(wav)
        val id = wav.nameWithoutExtension
        val deviceId = id.removePrefix("device-").takeIf { id.startsWith("device-") }?.toLongOrNull()
        val legacy = File(audioDir, "$id.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val transcript = legacy?.let { o ->
            val parts = o.optJSONArray("segments")
            Transcript((0 until (parts?.length() ?: 0)).map { i ->
                val p = parts!!.getJSONObject(i)
                Segment(p.getString("id"), p.getString("text"), p.optLong("startMs").takeIf { p.has("startMs") }, p.optLong("endMs").takeIf { p.has("endMs") })
            }, o.optString("model"), o.optString("backend"), o.optString("timestampQuality"), wav.lastModified())
        }
        return Recording(id, deviceId?.times(1000) ?: wav.lastModified(), pcm.durationMs,
            if (deviceId != null) RecordingSource.DEVICE else RecordingSource.IMPORT, deviceId,
            audioBytes = wav.length(), waveform = waveform(wav),
            stage = if (transcript != null) ProcessingStage.DONE else ProcessingStage.NEW, transcript = transcript)
    }

    suspend fun add(recording: Recording) {
        mutable.update { list -> (list.filterNot { it.id == recording.id } + recording).sortedByDescending { it.createdAt } }
        persist(recording)
    }

    suspend fun update(id: String, transform: (Recording) -> Recording): Recording? {
        var updated: Recording? = null
        mutable.update { list -> list.map { if (it.id == id) transform(it).also { r -> updated = r } else it } }
        updated?.let { persist(it) }
        return updated
    }

    /** Progress ticks update memory only; [update] persists. */
    fun updateTransient(id: String, transform: (Recording) -> Recording) {
        mutable.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            projection?.remove(id)
            mutable.update { list -> list.filterNot { it.id == id } }
            listOf("$id.wav", "$id.meta.json", "$id.json").forEach { File(audioDir, it).delete() }
        }
    }

    private suspend fun persist(requested: Recording) = withContext(Dispatchers.IO) {
        writes.withLock {
            val recording = mutable.value.firstOrNull { it.id == requested.id } ?: return@withLock
            if (!audioFile(recording.id).isFile) return@withLock
            val tmp = File.createTempFile("meta-", ".tmp", audioDir)
            try {
                tmp.outputStream().use { it.write(json.encodeToString(recording).toByteArray()); it.fd.sync() }
                Files.move(tmp.toPath(), File(audioDir, "${recording.id}.meta.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                projection?.upsert(recording)
            } finally { tmp.delete() }
        }
    }

    companion object {
        /** 44 normalized RMS bars sampled from the recording, as drawn on the home cards. */
        fun waveform(file: File, bars: Int = 44): List<Float> {
            val wav = PcmWav.open(file)
            if (wav.sampleCount == 0L) return emptyList()
            val bucket = wav.sampleCount / bars
            val values = (0 until bars).map { i ->
                val count = minOf(bucket, 8000L).toInt().coerceAtLeast(1)
                val start = (i * bucket + (bucket - count) / 2).coerceIn(0, wav.sampleCount - count)
                val samples = wav.readMono(start, count)
                sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
            }
            val peak = values.maxOrNull()?.takeIf { it > 0 } ?: return values.map { 0f }
            return values.map { (it / peak).coerceIn(0f, 1f) }
        }
    }
}
