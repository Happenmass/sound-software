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
class HttpAgentModel(private val config: LlmConfig, private val key: String?, client: OkHttpClient = OkHttpClient()) : AgentModel {
    private fun message(chinese: String, english: String) = if (config.outputLanguage == "en") english else chinese
    private val client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
    override suspend fun complete(messages: List<AgentMessage>, tools: Boolean): AgentReply {
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
        }
        val request = Request.Builder().url(config.chatUrl).post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }.build()
        val raw = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException(message("录音助手网络请求失败", "Could not connect to the assistant service."))) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        if (!it.isSuccessful) throw IOException(message("录音助手服务HTTP ${it.code}，请检查配置或服务状态", "The assistant service returned HTTP ${it.code}. Check its settings or status."))
                        val text = it.body?.string() ?: throw IOException(message("服务响应为空", "The assistant service returned an empty response."))
                        if (continuation.isActive) continuation.resume(text)
                    } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
        val root=Json.parseToJsonElement(raw).jsonObject
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
        val usage=root["usage"]?.takeUnless {it==JsonNull}?.jsonObject?.let {u->
            val prompt=u["prompt_tokens"]?.jsonPrimitive?.longOrNull
            val completion=u["completion_tokens"]?.jsonPrimitive?.longOrNull
            val total=u["total_tokens"]?.jsonPrimitive?.longOrNull ?: if(prompt!=null && completion!=null)prompt+completion else null
            if(prompt!=null && completion!=null && total!=null && prompt>=0 && completion>=0 && total>=0)AgentUsage(prompt,completion,total)else null
        }
        return AgentReply(message["content"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty(), calls,usage)
    }
}
