package dev.soundsoftware.core.agent

import dev.soundsoftware.core.analysis.LlmConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Native Chat Completions tool calls, provider transport isolated from the loop and UI. */
class HttpAgentModel(private val config: LlmConfig, private val key: String?, client: OkHttpClient = OkHttpClient(),
    private val onRequestMessages: ((List<AgentMessage>) -> Unit)? = null) : AgentModel {
    private fun message(chinese: String, english: String) = if (config.outputLanguage == "en") english else chinese
    private val client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
    override suspend fun complete(messages: List<AgentMessage>, tools: Boolean): AgentReply =
        request(messages, tools, null)

    override suspend fun completeStreaming(messages: List<AgentMessage>, onDraft: (String) -> Unit): AgentReply =
        request(messages, true, onDraft)

    private suspend fun request(messages: List<AgentMessage>, tools: Boolean, onDraft: ((String) -> Unit)?): AgentReply {
        onRequestMessages?.invoke(messages)
        val body = buildJsonObject {
            put("model", config.model); put("reasoning_effort",config.reasoningEffort)
            put("messages", buildJsonArray { messages.forEach { m -> add(buildJsonObject {
                put("role", m.role); put("content", m.content)
                m.toolCallId?.let { put("tool_call_id", it) }
                if (m.calls.isNotEmpty()) put("tool_calls", buildJsonArray { m.calls.forEach { c -> add(buildJsonObject {
                    put("id", c.id); put("type", "function"); putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                }) } })
            }) } })
            if (tools) { put("tools", AgentToolSchemas.json); put("tool_choice", "auto") }
            if (onDraft != null) {
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            if (config.chatUrl.host == "api.deepseek.com") {
                putJsonObject("thinking") { put("type", "disabled") }
            }
        }
        val request = Request.Builder().url(config.chatUrl).post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }.build()
        return suspendCancellableCoroutine<AgentReply> { continuation ->
            val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException(message("录音助手网络请求失败", "Could not connect to the assistant service."))) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        if (!it.isSuccessful) throw IOException(message("录音助手服务HTTP ${it.code}，请检查配置或服务状态", "The assistant service returned HTTP ${it.code}. Check its settings or status."))
                        val responseBody = it.body ?: throw IOException(message("服务响应为空", "The assistant service returned an empty response."))
                        val reply = if (onDraft != null && it.header("Content-Type").orEmpty().startsWith("text/event-stream", ignoreCase = true)) {
                            readStream(responseBody) { draft -> if (continuation.isActive) onDraft(draft) }
                        } else parseReply(Json.parseToJsonElement(responseBody.string()).jsonObject)
                        if (continuation.isActive) continuation.resume(reply)
                    } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }

    private fun parseReply(root: JsonObject): AgentReply {
        val choice = root.getValue("choices").jsonArray.first().jsonObject
        val finish = choice["finish_reason"]?.jsonPrimitive?.content
        require(finish in listOf("stop", "tool_calls")) { message("模型输出被截断或拒绝，请重试", "The model returned an incomplete answer or declined the request. Try again.") }
        val message = choice.getValue("message").jsonObject
        val calls = message["tool_calls"]?.jsonArray.orEmpty().map { e ->
            val c = e.jsonObject; require(c["type"]?.jsonPrimitive?.content == "function")
            val f = c.getValue("function").jsonObject
            val args = f.getValue("arguments").jsonPrimitive.content
            ToolCall(c.getValue("id").jsonPrimitive.content, f.getValue("name").jsonPrimitive.content, Json.parseToJsonElement(args).jsonObject)
        }
        return AgentReply(message["content"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty(), calls, usage(root))
    }

    private fun usage(root: JsonObject): AgentUsage? {
        val u = root["usage"] as? JsonObject ?: return null
        val prompt = u["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: return null
        val completion = u["completion_tokens"]?.jsonPrimitive?.longOrNull ?: return null
        val total = u["total_tokens"]?.jsonPrimitive?.longOrNull ?: (prompt + completion)
        return if (prompt >= 0 && completion >= 0 && total >= 0) AgentUsage(prompt, completion, total) else null
    }

    private class ToolDelta {
        val id = StringBuilder()
        val name = StringBuilder()
        val args = StringBuilder()
    }

    private fun readStream(body: ResponseBody, onDraft: (String) -> Unit): AgentReply {
        val source = body.source()
        val text = StringBuilder()
        val tools = sortedMapOf<Int, ToolDelta>()
        var usage: AgentUsage? = null
        var finish: String? = null
        var previousDraft = ""
        val event = mutableListOf<String>()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) event += line.substring(5).removePrefix(" ")
            if (line.isNotEmpty() || event.isEmpty()) continue
            val data = event.joinToString("\n")
            event.clear()
            if (data == "[DONE]") break
            val chunk = Json.parseToJsonElement(data).jsonObject
            if ("error" in chunk) throw IOException(message("录音助手服务返回错误", "The assistant service returned an error."))
            usage = usage(chunk) ?: usage
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
            finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: finish
            val delta = choice["delta"] as? JsonObject ?: continue
            (delta["tool_calls"] as? JsonArray)?.forEach { item ->
                val call = item.jsonObject
                val tool = tools.getOrPut(call.getValue("index").jsonPrimitive.int, ::ToolDelta)
                call["id"]?.jsonPrimitive?.contentOrNull?.let(tool.id::append)
                val function = call["function"] as? JsonObject
                function?.get("name")?.jsonPrimitive?.contentOrNull?.let(tool.name::append)
                function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let(tool.args::append)
            }
            delta["content"]?.jsonPrimitive?.contentOrNull?.let(text::append)
            if (tools.isEmpty()) {
                val draft = answerDraft(text)
                if (draft != previousDraft) {
                    previousDraft = draft
                    onDraft(draft)
                }
            }
        }
        require(finish == "stop" || finish == "tool_calls") {
            message("模型输出被截断或拒绝，请重试", "The model returned an incomplete answer or declined the request. Try again.")
        }
        return AgentReply(text.toString(), tools.values.map {
            ToolCall(it.id.toString(), it.name.toString(), Json.parseToJsonElement(it.args.toString()).jsonObject)
        }, usage)
    }
}


// Decode the incomplete JSON answer string, keeping tool arguments and citation metadata out of the UI.
private val answerField = Regex(""""answer"\s*:\s*"""")

private fun answerDraft(text: CharSequence): String {
    val start = answerField.find(text)?.range?.last?.plus(1) ?: return ""
    val escaped = StringBuilder()
    var i = start
    while (i < text.length && text[i] != '"') {
        if (text[i] == '\\') {
            val type = text.getOrNull(i + 1) ?: break
            val width = if (type == 'u') 6 else 2
            if (i + width > text.length) break
            escaped.append(text, i, i + width)
            i += width
        } else escaped.append(text[i++])
    }
    val value = runCatching { Json.parseToJsonElement("\"$escaped\"").jsonPrimitive.content }.getOrDefault("")
    return if (value.lastOrNull()?.isHighSurrogate() == true) value.dropLast(1) else value
}
