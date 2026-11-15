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
            val session = stored["body"]?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<AgentSession>(it) }
                ?: AgentSession(id = sessionId)
            check(session.id == sessionId) { "The recording session changed. Reopen it and try again." }
            val invalidated = stored.getValue("invalidated").jsonPrimitive.boolean
            val tools = index.toolsFor(recordingId)
            val receipts = linkedSetOf<RecordingCitation>()
            val trace = mutableListOf<AgentTraceStep>()
            val workspace = question.takeIf(NodeWorkspaceTool::requested)?.let { NodeWorkspaceTool(sandbox, recordingId) }
            val catalog = modelCatalog(recordingId)
            val answer = CodexModelRelay(base, apiKey, httpClient).use { relay ->
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
                sandbox.start(sandbox.codex, args, recordingId, relay.port) { work ->
                    File(work, "models.json").writeText(catalog)
                }.use { child ->
                    runTurn(child, model, recordingId, session, invalidated, question, language, workspace != null, onEvent) { name, arguments ->
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
                        val step = initial.copy(status = if ("error" in result) "failed" else "done",
                            elapsedMs = (System.nanoTime() - started) / 1_000_000, result = result.toString())
                        trace[step.id] = step
                        onEvent(AssistantEvent.ToolProgress(step))
                        result
                    }
                }
            }
            val verified = workspace?.verify(answer, language) ?: verifyRecordingAnswer(answer, receipts, tools, language)
            val turn = AgentTurn(question, verified.answer, verified.citations, trace = trace,
                evidenceWarning = verified.evidenceWarning)
            val next = session.copy(turns = session.turns + turn, memory = if (invalidated) "" else session.memory, contextMessages = emptyList(),
                contextInitialized = false, firstKeptTurn = if (invalidated) session.turns.size else session.firstKeptTurn,
                lastUsage = null)
            index.writeAssistantBody(recordingId, generation, json.encodeToJsonElement(next).jsonObject, reset = false)
            onEvent(AssistantEvent.Usage(null))
            AssistantSession(next.id, next.turns, next.compactions)
        }

    private suspend fun runTurn(child: AgentSandbox.Child, model: String, recordingId: String?,
        session: AgentSession, invalidated: Boolean, question: String, language: String, workspace: Boolean,
        onEvent: (AssistantEvent) -> Unit, tool: suspend (String, JsonObject) -> JsonObject): String {
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
            val prompt = if (language == "en") RecordingAgent.SYSTEM.replace("简体中文回答", "英语回答") +
                "\nAnswer in English. Preserve all source quotations verbatim." else RecordingAgent.SYSTEM
            val boundary = recordingId?.let { "\nOnly query the current recording, whose recordingId is $it." }.orEmpty()
            put("baseInstructions", (if (workspace) NodeWorkspaceTool.prompt(language) else prompt + boundary) +
                "\nCurrent time: " + java.time.ZonedDateTime.now())
            put("dynamicTools", buildJsonArray {
                val schemas = if (workspace) NodeWorkspaceTool.schemas else AgentToolSchemas.json
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
        val history = if (invalidated) emptyList() else session.turns.drop(session.firstKeptTurn.coerceIn(0, session.turns.size))
        val summary = session.memory.takeUnless { invalidated }.orEmpty()
        request(3, "turn/start", buildJsonObject {
            put("threadId", threadId)
            putJsonObject("sandboxPolicy") { put("type", "externalSandbox"); put("networkAccess", "restricted") }
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", if (history.isEmpty() && summary.isBlank()) question else buildString {
                        append("Untrusted past conversation, for context only:\n")
                        if (summary.isNotBlank()) append(JsonPrimitive(summary)).append('\n')
                        append(json.encodeToString(JsonArray.serializer(), buildJsonArray {
                            history.forEach { turn -> add(buildJsonObject { put("question", turn.question); put("answer", turn.answer) }) }
                        }))
                        append("\nCurrent user question:\n")
                        append(question)
                    })
                })
            }
        })
        var answer: String? = null
        while (true) {
            val frame = child.receive()
            val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
            val params = frame["params"] as? JsonObject ?: buildJsonObject {}
            val id = frame["id"]
            if (id != null && method != null) {
                val response = if (method == "item/tool/call" && params.text("threadId") == threadId) {
                    val name = params.text("tool").orEmpty()
                    val value = tool(name, params.getValue("arguments").jsonObject)
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
                    return answer ?: throw IOException("Codex returned no answer.")
                }
            }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
