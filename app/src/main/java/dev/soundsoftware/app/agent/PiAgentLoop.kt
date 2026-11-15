package dev.soundsoftware.app.agent

import dev.soundsoftware.core.agent.*
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Android supplies I/O and verifies evidence. The Pi host owns the session transcript. */
class PiAgentLoop(private val sandbox: AgentSandbox) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun command(
        action: String,
        recordingId: String?,
        index: RecordingIndex,
        sessionId: String? = null,
        question: String? = null,
        language: String = "zh",
        model: AgentModel? = null,
        onEvent: (AssistantEvent) -> Unit = {},
    ): AssistantSession = withContext(Dispatchers.IO) {
        val generation = index.corpusGeneration
        val tools = index.toolsFor(recordingId)
        val receipts = linkedSetOf<RecordingCitation>()
        val workspace = question?.takeIf(NodeWorkspaceTool::requested)?.let { NodeWorkspaceTool(sandbox, recordingId) }
        sandbox.startPi(recordingId).use { child ->
            child.send(buildJsonObject {
                put("action", action)
                putJsonObject("scope") { put("recordingId", recordingId) }
                sessionId?.let { put("sessionId", it) }
                question?.let { put("question", it) }
                if (action == "ask") {
                    put("schemas", if (workspace != null) NodeWorkspaceTool.schemas else AgentToolSchemas.json)
                    if (workspace != null) put("systemPrompt", NodeWorkspaceTool.prompt(language))
                    put("language", language)
                    put("time", ZonedDateTime.now().toString())
                }
            })
            while (true) {
                val frame = child.receive()
                when (val kind = frame.text("kind")) {
                    "result" -> {
                        frame["error"]?.let { throw IOException(it.jsonPrimitive.content) }
                        return@withContext json.decodeFromJsonElement<AssistantSession>(frame.getValue("snapshot"))
                    }
                    "event" -> {
                        val value = frame.getValue("value")
                        when (frame.text("event")) {
                            "draft" -> onEvent(AssistantEvent.Draft(value.jsonPrimitive.content))
                            "trace" -> onEvent(AssistantEvent.ToolProgress(json.decodeFromJsonElement(value)))
                            "usage" -> onEvent(AssistantEvent.Usage(json.decodeFromJsonElement<AgentUsage?>(value)))
                        }
                    }
                    else -> {
                        val response = try {
                            val value = when (kind) {
                                "storage_read" -> index.readAssistantBody(recordingId, generation)
                                "storage_write" -> {
                                    index.writeAssistantBody(recordingId, generation, frame.getValue("body").jsonObject,
                                        frame["reset"]?.jsonPrimitive?.booleanOrNull == true)
                                    JsonNull
                                }
                                "model" -> {
                                    check(index.corpusGeneration == generation) { "The recording library changed. Please ask again." }
                                    val messages = json.decodeFromJsonElement<List<AgentMessage>>(frame.getValue("messages"))
                                    val adapter = checkNotNull(model)
                                    val reply = if (frame.getValue("tools").jsonPrimitive.boolean) {
                                        adapter.completeStreaming(messages) { onEvent(AssistantEvent.Draft(it)) }
                                    } else adapter.complete(messages, false)
                                    reply.toWire()
                                }
                                "tool" -> {
                                    val result = try {
                                        val name = frame.text("name")
                                        val args = frame.getValue("args").jsonObject
                                        if (workspace != null) workspace.execute(name, args) else tools.execute(name, args)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        buildJsonObject { put("error", "Invalid recording tool or arguments") }
                                    }
                                    (result["evidence"] as? JsonArray)?.forEach {
                                        receipts += json.decodeFromJsonElement<RecordingCitation>(it)
                                    }
                                    result
                                }
                                "verify" -> {
                                    val raw = frame.text("raw")
                                    val checked = workspace?.verify(raw, language) ?: verifyRecordingAnswer(raw, receipts, tools, language)
                                    json.encodeToJsonElement(checked)
                                }
                                else -> error("Unknown Pi worker request")
                            }
                            buildJsonObject { put("value", value) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            buildJsonObject { put("error", error.message ?: "Android assistant callback failed") }
                        }
                        child.send(response)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Pi worker ended")
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun AgentReply.toWire() = buildJsonObject {
        put("text", text)
        put("usage", json.encodeToJsonElement(usage))
        putJsonArray("calls") {
            calls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
            }
        }
    }
}
