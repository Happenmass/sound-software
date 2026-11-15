package dev.soundsoftware.app.agent

import dev.soundsoftware.core.agent.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

/** A Codex thread lives in the turn directory. Verified history belongs to the recording index. */
internal class CodexAgentLoop(private val sandbox: AgentSandbox, private val index: RecordingIndex,
    private val httpClient: okhttp3.OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var directToolCatalog: String? = null

    private suspend fun modelCatalog(recordingId: String?): String {
        directToolCatalog?.let { return it }
        val (exitCode, raw) = sandbox.start(sandbox.codex, listOf("debug", "models", "--bundled"), recordingId).use {
            withTimeout(10_000) { it.capture(2 * 1024 * 1024) }
        }
        check(exitCode == 0) { "Codex could not load its bundled model catalog." }
        val catalog = Json.parseToJsonElement(raw).jsonObject
        val models = catalog.getValue("models").jsonArray.map { model ->
            // The Android integration dispatches tools over App Server RPC.
            JsonObject(model.jsonObject + ("tool_mode" to JsonPrimitive("direct")))
        }
        return JsonObject(catalog + ("models" to JsonArray(models))).toString().also { directToolCatalog = it }
    }

    suspend fun ask(recordingId: String?, sessionId: String, question: String, language: String,
        base: HttpUrl, model: String, apiKey: String?, onEvent: (AssistantEvent) -> Unit): AssistantSession =
        withContext(Dispatchers.IO) {
            val generation = index.corpusGeneration
            val stored = index.readAssistantBody(recordingId, generation)
            val saved = stored["body"]?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<AgentSession>(it) }
                ?: AgentSession(id = sessionId)
            check(saved.id == sessionId) { "The recording session changed. Reopen it and try again." }
            var session = saved.initializeContext(stored.getValue("invalidated").jsonPrimitive.boolean)
            val tools = index.toolsFor(recordingId)
            val receipts = linkedSetOf<RecordingCitation>()
            val trace = mutableListOf<AgentTraceStep>()
            val workspace = question.takeIf(NodeWorkspaceTool::requested)?.let { NodeWorkspaceTool(sandbox, recordingId) }
            val catalog = modelCatalog(recordingId)
            val next = CodexModelRelay(base, apiKey, httpClient).use { relay ->
                val args = listOf(
                    "-c", "model_provider=\"sound\"",
                    "-c", "model_providers.sound.name=\"Sound Software\"",
                    "-c", "model_providers.sound.base_url=${JsonPrimitive(relay.localBase)}",
                    "-c", "model_providers.sound.wire_api=\"responses\"",
                    "-c", "web_search=\"disabled\"",
                    "-c", "model_catalog_json=\"models.json\"",
                    "--disable", "shell_tool",
                    "--disable", "shell_snapshot",
                    "--disable", "code_mode_host",
                    "--disable", "incremental_tools",
                    "app-server", "--listen", "stdio://",
                )
                suspend fun execute(input: String, instructions: String, schemas: JsonArray,
                    events: (AssistantEvent) -> Unit = onEvent,
                    tool: suspend (String, String, JsonObject) -> JsonObject = { _, _, _ -> error("No tools in compaction") },
                ): AgentReply = sandbox.start(sandbox.codex, args, recordingId, relay.port) { work ->
                    File(work, "models.json").writeText(catalog)
                }.use { child -> runTurn(child, model, input, instructions, schemas, events, tool) }

                suspend fun compact(candidate: AgentSession): AgentSession = candidate.compactContext(question) { messages ->
                    check(index.corpusGeneration == generation) { "The recording library changed. Please ask again." }
                    val step = AgentTraceStep(trace.size, "compact", if (language == "en") "Compact context" else "压缩上下文")
                    trace += step
                    onEvent(AssistantEvent.ToolProgress(step))
                    val reply = execute(messages.last().content, messages.first().content, JsonArray(emptyList()), events = {})
                    trace[step.id] = step.copy(status = "done")
                    onEvent(AssistantEvent.ToolProgress(trace[step.id]))
                    onEvent(AssistantEvent.Usage(null))
                    reply
                }

                session = compact(session)
                val messages = mutableListOf<AgentMessage>()
                val prompt = if (language == "en") AgentPrompts.SYSTEM.replace("简体中文回答", "英语回答") +
                    "\nAnswer in English. Preserve all source quotations verbatim." else AgentPrompts.SYSTEM
                val boundary = recordingId?.let { "\nOnly query the current recording, whose recordingId is $it." }.orEmpty()
                val instructions = (workspace?.let { NodeWorkspaceTool.prompt(language) } ?: (prompt + boundary)) +
                    "\nCurrent time: " + java.time.ZonedDateTime.now()
                val reply = execute(session.codexInput(question), instructions,
                    if (workspace != null) NodeWorkspaceTool.schemas else AgentToolSchemas.json) { callId, name, arguments ->
                    check(index.corpusGeneration == generation) { "The recording library changed. Please ask again." }
                    val initial = AgentTraceStep(id = trace.size, kind = "tool", title = name,
                        toolName = name, arguments = arguments.toString())
                    trace += initial
                    onEvent(AssistantEvent.ToolProgress(initial))
                    val started = System.nanoTime()
                    val result = try {
                        if (workspace != null) workspace.execute(name, arguments) else tools.execute(name, arguments)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        buildJsonObject { put("error", "Invalid recording tool or arguments") }
                    }
                    (result["evidence"] as? JsonArray)?.forEach {
                        receipts += json.decodeFromJsonElement<RecordingCitation>(it)
                    }
                    messages += AgentMessage("assistant", calls = listOf(WireToolCall(callId, name, arguments.toString())))
                    messages += AgentMessage("tool", result.toString(), toolCallId = callId)
                    val step = initial.copy(status = if ("error" in result) "failed" else "done",
                        elapsedMs = (System.nanoTime() - started) / 1_000_000, result = result.toString())
                    trace[step.id] = step
                    onEvent(AssistantEvent.ToolProgress(step))
                    result
                }
                val verified = workspace?.verify(reply.text, language) ?: verifyRecordingAnswer(reply.text, receipts, tools, language)
                val turn = AgentTurn(question, verified.answer, verified.citations, evidenceWarning = verified.evidenceWarning)
                val completed = compact(session.completeTurn(turn, messages, reply.usage))
                completed.copy(turns = completed.turns.dropLast(1) + turn.copy(trace = trace.toList()))
            }
            index.writeAssistantBody(recordingId, generation, json.encodeToJsonElement(next).jsonObject, reset = false)
            onEvent(AssistantEvent.Usage(next.lastUsage))
            AssistantSession(next.id, next.turns, next.compactions, next.lastUsage)
        }

    private suspend fun runTurn(child: AgentSandbox.Child, model: String, input: String,
        instructions: String, schemas: JsonArray, onEvent: (AssistantEvent) -> Unit,
        tool: suspend (String, String, JsonObject) -> JsonObject): AgentReply {
        fun request(id: Int?, method: String, params: JsonObject = buildJsonObject {}) = child.send(buildJsonObject {
            id?.let { put("id", it) }
            put("method", method)
            put("params", params)
        })
        suspend fun result(id: Int): JsonObject = withTimeout(15_000) {
            var frame: JsonObject
            do { frame = child.receive() } while ((frame["id"] as? JsonPrimitive)?.intOrNull != id)
            frame["result"]?.jsonObject ?: throw IOException("Codex rejected the assistant request.")
        }

        request(1, "initialize", buildJsonObject {
            putJsonObject("clientInfo") { put("name", "sound_software"); put("version", "1") }
            putJsonObject("capabilities") { put("experimentalApi", true) }
        })
        result(1)
        request(null, "initialized")
        request(2, "thread/start", buildJsonObject {
            put("cwd", child.work.absolutePath)
            put("model", model)
            put("modelProvider", "sound")
            put("ephemeral", true)
            put("approvalPolicy", "never")
            put("sandbox", "read-only")
            put("baseInstructions", instructions)
            put("dynamicTools", buildJsonArray {
                schemas.forEach { entry ->
                    val function = entry.jsonObject.getValue("function").jsonObject
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", function.getValue("name"))
                        put("description", function.getValue("description"))
                        put("inputSchema", function.getValue("parameters"))
                        put("deferLoading", false)
                    })
                }
            })
        })
        val threadId = result(2).getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
        request(3, "turn/start", buildJsonObject {
            put("threadId", threadId)
            putJsonObject("sandboxPolicy") { put("type", "externalSandbox"); put("networkAccess", "restricted") }
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", input)
                })
            }
        })
        var answer: String? = null
        var usage: AgentUsage? = null
        while (true) {
            val frame = child.receive()
            val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
            val params = frame["params"] as? JsonObject ?: buildJsonObject {}
            val id = frame["id"]
            if (id != null && method != null) {
                val response = if (method == "item/tool/call" && params.text("threadId") == threadId) {
                    val name = params.text("tool").orEmpty()
                    check(schemas.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name }) { "Unregistered Codex tool" }
                    val value = tool(params.getValue("callId").jsonPrimitive.content, name, params.getValue("arguments").jsonObject)
                    buildJsonObject {
                        put("success", "error" !in value)
                        putJsonArray("contentItems") {
                            add(buildJsonObject { put("type", "inputText"); put("text", value.toString()) })
                        }
                    }
                } else null
                child.send(buildJsonObject {
                    put("id", id)
                    if (response == null) putJsonObject("error") { put("code", -32601); put("message", "Unsupported request") }
                    else put("result", response)
                })
                continue
            }
            if (id == JsonPrimitive(3) && "error" in frame) throw IOException("Codex could not start the turn.")
            if (params.text("threadId") != threadId) continue
            when (method) {
                "thread/tokenUsage/updated" -> {
                    val last = params.getValue("tokenUsage").jsonObject.getValue("last").jsonObject
                    usage = AgentUsage(last.getValue("inputTokens").jsonPrimitive.long,
                        last.getValue("outputTokens").jsonPrimitive.long, last.getValue("totalTokens").jsonPrimitive.long)
                    onEvent(AssistantEvent.Usage(usage))
                }
                "item/completed" -> {
                    val item = params.getValue("item").jsonObject
                    if (item.text("type") == "agentMessage" && item.text("phase") != "commentary") {
                        answer = item.text("text")
                        // Show only answer text, never partial JSON or tool arguments.
                        runCatching { Json.parseToJsonElement(answer.orEmpty()).jsonObject.text("answer") }
                            .getOrNull()?.let { onEvent(AssistantEvent.Draft(it)) }
                    }
                }
                "turn/completed" -> {
                    val turn = params.getValue("turn").jsonObject
                    if (turn.text("status") != "completed") throw IOException("Codex could not complete the turn. Check the Responses API settings.")
                    return AgentReply(answer ?: throw IOException("Codex returned no answer."), usage = usage)
                }
            }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
