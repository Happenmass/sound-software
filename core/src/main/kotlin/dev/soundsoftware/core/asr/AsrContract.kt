package dev.soundsoftware.core.asr

import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExecutionLocation { LOCAL, REMOTE }
enum class TimestampQuality { NONE, APPROXIMATE_SEGMENT, SEGMENT, WORD }
enum class TimestampRequirement { NONE, BEST_EFFORT, REQUIRE_SEGMENT, REQUIRE_WORD }
enum class ErrorCode { MODEL_NOT_READY, BACKEND_UNAVAILABLE, INVALID_AUDIO, INPUT_TOO_LARGE, UNSUPPORTED_CAPABILITY, AUTHENTICATION, PERMISSION, RATE_LIMIT, NETWORK, TIMEOUT, SERVER_UNAVAILABLE, REMOTE_STATE_UNKNOWN, INVALID_RESPONSE, OUT_OF_MEMORY }

class AsrException(val code: ErrorCode, message: String, val retryable: Boolean = false) : Exception(message)

data class AsrCapabilities(
    val timestamps: TimestampQuality = TimestampQuality.NONE,
    val languageHint: Boolean = false,
    val contextHint: Boolean = false,
    val speakerLabels: Boolean = false,
    val textNormalization: Boolean = false,
    val maxInputBytes: Long? = null,
)
data class AsrDescriptor(val id: String, val model: String, val location: ExecutionLocation, val capabilities: AsrCapabilities)

data class AudioInput(val id: String, val file: File, val sha256: String, val sampleCount: Long, val sampleRate: Int = 16000) {
    val durationMs: Long get() = sampleCount * 1000 / sampleRate
    companion object {
        fun fromWav(file: File, id: String = file.nameWithoutExtension): AudioInput {
            val wav = PcmWav.open(file)
            require(wav.sampleRate == 16000 && wav.channels == 1) { "Expected 16kHz mono PCM16" }
            return AudioInput(id, file, file.sha256(), wav.sampleCount)
        }
    }
}
data class AsrOptions(
    val language: String? = null,
    val hotwords: List<String> = emptyList(),
    val timestamps: TimestampRequirement = TimestampRequirement.BEST_EFFORT,
    val requireSpeakerLabels: Boolean = false,
    val textNormalization: Boolean = false,
)
data class AsrRequest(val jobId: String, val attemptId: String, val audio: AudioInput, val profileRevision: String, val options: AsrOptions = AsrOptions())
data class TranscriptSegment(val id: String, val text: String, val startMs: Long? = null, val endMs: Long? = null, val speaker: String? = null)
data class AsrProvenance(val adapterId: String, val model: String, val runtime: String, val backend: String)
data class AsrResult(
    val audioId: String,
    val inputHash: String,
    val profileRevision: String,
    val text: String,
    val segments: List<TranscriptSegment>,
    val timestampQuality: TimestampQuality,
    val provenance: AsrProvenance,
    val warnings: List<String> = emptyList(),
    val elapsedMs: Long = 0,
)
enum class AsrStage { PREPARING, UPLOADING, RECOGNIZING, FINALIZING }
data class AsrEvent(val stage: AsrStage, val fraction: Double? = null, val message: String? = null)
data class AsrExecutionContext(
    val checkpoints: CheckpointStore = MemoryCheckpointStore(),
    val onEvent: suspend (AsrEvent) -> Unit = {},
)

interface AsrEngine : AutoCloseable {
    val descriptor: AsrDescriptor
    suspend fun transcribe(request: AsrRequest, context: AsrExecutionContext = AsrExecutionContext()): AsrResult
    override fun close() {}
}

fun validateOptions(options: AsrOptions, capabilities: AsrCapabilities) {
    fun reject(message: String): Nothing = throw AsrException(ErrorCode.UNSUPPORTED_CAPABILITY, message)
    if (options.timestamps == TimestampRequirement.REQUIRE_WORD && capabilities.timestamps != TimestampQuality.WORD) reject("此配置不提供词级时间戳")
    if (options.timestamps == TimestampRequirement.REQUIRE_SEGMENT && capabilities.timestamps !in listOf(TimestampQuality.SEGMENT, TimestampQuality.WORD)) reject("此配置不提供准确段级时间戳")
    if (options.language != null && !capabilities.languageHint) reject("此配置不支持指定语言")
    if (options.hotwords.isNotEmpty() && !capabilities.contextHint) reject("此配置不支持热词提示")
    if (options.requireSpeakerLabels && !capabilities.speakerLabels) reject("此配置不提供说话人标签")
    if (options.textNormalization && !capabilities.textNormalization) reject("此配置未验证文本规范化选项")
}

suspend fun validateAudio(audio: AudioInput): PcmWav = withContext(Dispatchers.IO) {
    try {
        val wav = PcmWav.open(audio.file)
        require(wav.sampleRate == 16000 && wav.channels == 1 && audio.sampleRate == 16000 && wav.sampleCount == audio.sampleCount)
        require(audio.file.sha256() == audio.sha256) { "Audio changed since import" }
        wav
    } catch (e: Exception) {
        throw AsrException(ErrorCode.INVALID_AUDIO, "录音不存在、已变化或不是完整的 16kHz 单声道 WAV")
    }
}

fun AsrResult.validate(request: AsrRequest): AsrResult {
    fun invalid(): Nothing = throw AsrException(ErrorCode.INVALID_RESPONSE, "转写结果与输入或时间轴不一致")
    if (audioId != request.audio.id || inputHash != request.audio.sha256 || profileRevision != request.profileRevision) invalid()
    if (segments.map { it.id }.toSet().size != segments.size) invalid()
    var previousStart = -1L
    segments.forEach { segment ->
        val start = segment.startMs; val end = segment.endMs
        if ((start == null) != (end == null)) invalid()
        if (start != null && end != null) {
            if (start < 0 || start >= end || end > request.audio.durationMs || start < previousStart) invalid()
            previousStart = start
        }
    }
    if (timestampQuality == TimestampQuality.NONE && segments.any { it.startMs != null }) invalid()
    if (timestampQuality != TimestampQuality.NONE && segments.any { it.startMs == null }) invalid()
    if (request.options.timestamps == TimestampRequirement.REQUIRE_SEGMENT && timestampQuality !in listOf(TimestampQuality.SEGMENT, TimestampQuality.WORD)) invalid()
    if (request.options.timestamps == TimestampRequirement.REQUIRE_WORD && timestampQuality != TimestampQuality.WORD) invalid()
    return this
}
