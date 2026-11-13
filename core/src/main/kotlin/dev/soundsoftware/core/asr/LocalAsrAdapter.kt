package dev.soundsoftware.core.asr

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
            require(runtime.maxSamples in 1..dev.soundsoftware.core.audio.PcmWav.MAX_WINDOW_SAMPLES)
            val ranges = splitSpeechRanges(vad.detect(wav), runtime.maxSamples)
            val key = checkpointKey(request, descriptor) + "|${vad.id}|${runtime.maxSamples}"
            val segments = mutableListOf<TranscriptSegment>()
            var loaded = false
            for ((index, range) in ranges.withIndex()) {
                currentCoroutineContext().ensureActive()
                val partKey = "$key|${range.startSample}|${range.endSample}"
                val cached = context.checkpoints.read(partKey)
                val text = if (cached != null) cached else {
                    if (!loaded) { runtime.prepare(); loaded = true }
                    currentCoroutineContext().ensureActive()
                    val samples = wav.readMono(range.startSample, (range.endSample - range.startSample).toInt())
                    val result = runtime.decode(samples, wav.sampleRate)
                    currentCoroutineContext().ensureActive()
                    result.text.trim().also { context.checkpoints.write(partKey, it) }
                }
                if (text.isNotBlank()) {
                    segments += TranscriptSegment(
                        "part-$index", text, range.startSample * 1000 / wav.sampleRate,
                        range.endSample * 1000 / wav.sampleRate,
                    )
                }
                context.onEvent(AsrEvent(AsrStage.RECOGNIZING, (index + 1).toDouble() / ranges.size))
            }
            context.onEvent(AsrEvent(AsrStage.FINALIZING))
            AsrResult(
                request.audio.id, request.audio.sha256, request.profileRevision,
                segments.joinToString("\n") { it.text }, segments, TimestampQuality.APPROXIMATE_SEGMENT,
                AsrProvenance(descriptor.id, runtime.modelId, runtime.runtimeName, runtime.backend),
                warnings = listOf("时间戳为分析窗口范围，不是模型对齐；窗口边界识别质量尚需真录音验收", "VAD: ${vad.id}"),
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            ).validate(request)
        }
    }

    /** Call only after transcribe has returned/cancelled, never during native decode. */
    override fun close() { runtime.close() }
}
