package dev.soundsoftware.core.asr

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

/** Exact transcription endpoint, not a base URL. Abilities must be verified for that endpoint/model. */
data class HttpAsrConfig(
    val endpoint: HttpUrl,
    val model: String,
    val supportsSegmentTimestamps: Boolean = false,
    val supportsLanguageHint: Boolean = false,
    val supportsContextHint: Boolean = false,
    val maxInputBytes: Long = 25L * 1024 * 1024,
    val allowInsecureLoopbackForTests: Boolean = false,
) {
    init {
        require(model.isNotBlank() && maxInputBytes > 0)
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null)
        require(endpoint.isHttps || (allowInsecureLoopbackForTests && endpoint.host in listOf("localhost", "127.0.0.1", "::1"))) {
            "ASR endpoint must use HTTPS"
        }
    }
}

class HttpFileAsrAdapter(
    private val config: HttpAsrConfig,
    private val bearerToken: () -> String?,
    client: OkHttpClient = OkHttpClient(),
) : AsrEngine {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.MINUTES).build()
    private val execution = Mutex()
    override val descriptor = AsrDescriptor(
        "http-file", config.model, ExecutionLocation.REMOTE,
        AsrCapabilities(
            timestamps = if (config.supportsSegmentTimestamps) TimestampQuality.SEGMENT else TimestampQuality.NONE,
            languageHint = config.supportsLanguageHint, contextHint = config.supportsContextHint,
            maxInputBytes = config.maxInputBytes,
        ),
    )

    override suspend fun transcribe(request: AsrRequest, context: AsrExecutionContext): AsrResult = execution.withLock {
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            validateOptions(request.options, descriptor.capabilities)
            validateAudio(request.audio)
            if (request.audio.file.length() > config.maxInputBytes) throw AsrException(ErrorCode.INPUT_TOO_LARGE, "录音超过此接口配置的上传限制；请换本地推理或更大限额的服务")
            val key = checkpointKey(request, descriptor) + "|" + config.endpoint
            val prior = context.checkpoints.read(key)
            if (prior == "submitted") throw AsrException(ErrorCode.REMOTE_STATE_UNKNOWN, "上一次请求可能已被服务处理；请选择新任务重新提交，不自动重复上传")
            if (prior != null) return@withContext parseResult(prior, request, 0)
            val token = bearerToken()?.takeIf { it.isNotBlank() }
            if (token?.contains('\n') == true || token?.contains('\r') == true) throw AsrException(ErrorCode.AUTHENTICATION, "凭证格式无效")
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("response_format", if (config.supportsSegmentTimestamps) "verbose_json" else "json")
                .addFormDataPart("file", "recording.wav", request.audio.file.asRequestBody("audio/wav".toMediaType()))
            request.options.language?.let { multipart.addFormDataPart("language", it) }
            if (request.options.hotwords.isNotEmpty()) multipart.addFormDataPart("prompt", request.options.hotwords.joinToString(", "))
            val http = Request.Builder().url(config.endpoint).post(multipart.build())
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            context.onEvent(AsrEvent(AsrStage.UPLOADING))
            // Write before dispatch: a crash/cancel must never silently cause another paid submission.
            context.checkpoints.write(key, "submitted")
            val reply = client.newCall(http).awaitReply()
            if (reply.status !in 200..299) {
                // Explicit client rejection means a new attempt can be chosen safely; no automatic retries.
                if (reply.status in 400..499) context.checkpoints.remove(key)
                val code = when (reply.status) {
                    401 -> ErrorCode.AUTHENTICATION
                    403 -> ErrorCode.PERMISSION
                    413 -> ErrorCode.INPUT_TOO_LARGE
                    429 -> ErrorCode.RATE_LIMIT
                    in 500..599 -> ErrorCode.REMOTE_STATE_UNKNOWN
                    else -> ErrorCode.INVALID_RESPONSE
                }
                throw AsrException(code, "ASR 服务返回 HTTP ${reply.status}", reply.status == 429)
            }
            context.onEvent(AsrEvent(AsrStage.FINALIZING))
            val result = parseResult(reply.body, request, (System.nanoTime() - started) / 1_000_000)
            context.checkpoints.write(key, reply.body)
            result
        }
    }

    private fun parseResult(body: String, request: AsrRequest, elapsed: Long): AsrResult {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val text = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Missing text")
            val sourceSegments = root["segments"] as? JsonArray
            val segments = sourceSegments?.mapIndexed { index, element ->
                val segment = element.jsonObject
                val start = segment["start"].secondsToMs()
                val end = segment["end"].secondsToMs()
                val segmentText = (segment["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: throw IllegalArgumentException("Missing segment text")
                TranscriptSegment("segment-$index", segmentText, start, end)
            } ?: if (text.isBlank()) emptyList() else listOf(TranscriptSegment("text-0", text))
            val hasTimes = segments.isNotEmpty() && segments.all { it.startMs != null }
            val quality = if (hasTimes) TimestampQuality.SEGMENT else TimestampQuality.NONE
            val warnings = if (!hasTimes && request.options.timestamps != TimestampRequirement.NONE) listOf("服务未返回可靠时间戳，展示纯文本") else emptyList()
            return AsrResult(request.audio.id, request.audio.sha256, request.profileRevision, text, segments, quality,
                AsrProvenance(descriptor.id, config.model, "http-file-v1", "remote"), warnings, elapsed).validate(request)
        } catch (e: AsrException) { throw e }
        catch (e: Exception) { throw AsrException(ErrorCode.INVALID_RESPONSE, "ASR 响应缺少正文或含有无效时间戳") }
    }
}

private fun kotlinx.serialization.json.JsonElement?.secondsToMs(): Long? {
    if (this == null || this == kotlinx.serialization.json.JsonNull) return null
    val seconds = (this as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("Invalid timestamp")
    require(seconds.isFinite() && seconds >= 0 && seconds < Long.MAX_VALUE / 1000.0)
    return (seconds * 1000).toLong()
}

private data class HttpReply(val status: Int, val body: String)
private suspend fun Call.awaitReply(): HttpReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(
                AsrException(ErrorCode.REMOTE_STATE_UNKNOWN, "网络请求中断，服务端执行状态未知；未自动重复提交"))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val reply = response.use {
                    val source = it.body?.source() ?: throw IOException("Empty response")
                    val limit = 8L * 1024 * 1024
                    source.request(limit + 1)
                    if (source.buffer.size > limit) throw IOException("Response too large")
                    HttpReply(it.code, source.readUtf8())
                }
                if (continuation.isActive) continuation.resume(reply)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    AsrException(ErrorCode.INVALID_RESPONSE, "ASR 响应读取失败或超出大小限制"))
            }
        }
    })
}
