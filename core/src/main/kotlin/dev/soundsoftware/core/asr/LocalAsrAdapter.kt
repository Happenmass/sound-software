package dev.soundsoftware.core.asr

import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.transcript.SpeakerTurn
import dev.soundsoftware.core.transcript.groupSpeakerTurns
import dev.soundsoftware.core.vad.SpeechRange
import dev.soundsoftware.core.vad.VoiceActivityDetector
import dev.soundsoftware.core.vad.WholeRecordingVad
import dev.soundsoftware.core.vad.splitSpeechRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalRecognition(val text: String)

/** JNI and model tensors stay behind this seam. decode must not truncate the supplied window. */
interface LocalAsrRuntime : AutoCloseable {
    val modelId: String
    val runtimeName: String
    val backend: String
    val maxSamples: Int
    suspend fun prepare()
    suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition
}

class LocalAsrAdapter(
    private val runtime: LocalAsrRuntime,
    private val vad: VoiceActivityDetector = WholeRecordingVad(),
    private val speakerTurns: List<SpeakerTurn> = emptyList(),
) : AsrEngine {
    private val execution = Mutex()
    override val descriptor = AsrDescriptor(
        "sherpa-local", runtime.modelId, ExecutionLocation.LOCAL,
        AsrCapabilities(timestamps = TimestampQuality.APPROXIMATE_SEGMENT),
    )

    override suspend fun transcribe(request: AsrRequest, context: AsrExecutionContext): AsrResult = execution.withLock {
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            validateOptions(request.options, descriptor.capabilities)
            context.onEvent(AsrEvent(AsrStage.PREPARING))
            val wav = validateAudio(request.audio)
            require(runtime.maxSamples in 1..PcmWav.MAX_WINDOW_SAMPLES)
            val groups = groupSpeakerTurns(speakerTurns, wav.durationMs)
            val inputPlan = if (groups.isEmpty()) vad.id else "speaker-turns-v2"
            val batches = if (groups.isEmpty()) {
                splitSpeechRanges(vad.detect(wav), runtime.maxSamples).map { RecognitionBatch(listOf(it)) }
            } else groups.map { group ->
                RecognitionBatch(speakerWindows(group, wav.sampleRate, runtime.maxSamples), group.first().speaker)
            }
            val key = checkpointKey(request, descriptor) + "|$inputPlan|${runtime.maxSamples}"
            val segments = mutableListOf<TranscriptSegment>()
            var loaded = false
            var completed = 0
            val total = batches.sumOf { it.windows.size }
            for ((index, batch) in batches.withIndex()) {
                val recognized = mutableListOf<String>()
                for (range in batch.windows) {
                    currentCoroutineContext().ensureActive()
                    val partKey = "$key|${range.startSample}|${range.endSample}"
                    val text = context.checkpoints.read(partKey) ?: run {
                        if (!loaded) { runtime.prepare(); loaded = true }
                        currentCoroutineContext().ensureActive()
                        val samples = wav.readMono(range.startSample, (range.endSample - range.startSample).toInt())
                        val result = runtime.decode(samples, wav.sampleRate)
                        currentCoroutineContext().ensureActive()
                        result.text.trim().also { context.checkpoints.write(partKey, it) }
                    }
                    if (text.isNotBlank()) recognized += text
                    completed++
                    context.onEvent(AsrEvent(AsrStage.RECOGNIZING, completed.toDouble() / total))
                }
                if (recognized.isNotEmpty()) {
                    segments += TranscriptSegment(
                        "part-$index", recognized.joinToString(" "), batch.windows.first().startSample * 1000 / wav.sampleRate,
                        batch.windows.last().endSample * 1000 / wav.sampleRate,
                        batch.speaker?.takeIf { it >= 0 }?.let { "spk-$it" },
                        fragments = if (recognized.size > 1) recognized else emptyList(),
                    )
                }
            }
            context.onEvent(AsrEvent(AsrStage.FINALIZING))
            AsrResult(
                request.audio.id, request.audio.sha256, request.profileRevision,
                segments.joinToString("\n") { it.text }, segments, TimestampQuality.APPROXIMATE_SEGMENT,
                AsrProvenance(descriptor.id, runtime.modelId, runtime.runtimeName, runtime.backend),
                warnings = listOf("时间戳为分析窗口范围，不是模型对齐；窗口边界识别质量尚需真录音验收", "VAD: $inputPlan"),
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            ).validate(request)
        }
    }

    /** Call only after transcribe has returned/cancelled, never during native decode. */
    override fun close() { runtime.close() }
}

private data class RecognitionBatch(val windows: List<SpeechRange>, val speaker: Int? = null)

private fun speakerWindows(turns: List<SpeakerTurn>, sampleRate: Int, maxSamples: Int): List<SpeechRange> {
    val ranges = turns.map { SpeechRange(it.startMs * sampleRate / 1000, it.endMs * sampleRate / 1000) }
    val maxGapSamples = 600L * sampleRate / 1000
    val windows = mutableListOf<SpeechRange>()
    for (range in splitSpeechRanges(ranges, maxSamples)) {
        val last = windows.lastOrNull()
        if (last != null && range.startSample - last.endSample <= maxGapSamples && range.endSample - last.startSample <= maxSamples) {
            windows[windows.lastIndex] = last.copy(endSample = range.endSample)
        } else {
            windows += range
        }
    }
    return windows
}
