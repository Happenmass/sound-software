package dev.soundsoftware.app.agent

import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.data.AppSettings
import dev.soundsoftware.app.data.AssistantBackend
import dev.soundsoftware.app.data.AssistantModelSettings
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.LlmConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class AssistantSession(
    val id: String = UUID.randomUUID().toString(),
    val turns: List<AgentTurn> = emptyList(),
    val compactions: Int = 0,
    val lastUsage: AgentUsage? = null,
)

private fun AgentSession.snapshot() = AssistantSession(id, turns, compactions, lastUsage)

data class AssistantState(
    val session: AssistantSession = AssistantSession(),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val runId: String? = null,
    val pendingQuestion: String? = null,
    val draft: String = "",
    val trace: List<AgentTraceStep> = emptyList(),
    val error: String? = null,
    val cancelled: Boolean = false,
    val contextUsage: AgentUsage? = null,
)

sealed interface AssistantEvent {
    data class Started(val question: String) : AssistantEvent
    data class Draft(val text: String) : AssistantEvent
    data class ToolProgress(val step: AgentTraceStep) : AssistantEvent
    data class Usage(val usage: AgentUsage?) : AssistantEvent
    data class Completed(val session: AssistantSession) : AssistantEvent
    data class Failed(val message: String) : AssistantEvent
    data object Cancelled : AssistantEvent
}

data class AssistantUpdate(val sessionId: String, val runId: String, val event: AssistantEvent)

class AgentController(
    private val index: RecordingIndex,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val sandbox: AgentSandbox,
    private val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
) {
    private val activeTurn = Mutex()
    private val pi = PiAgentLoop(sandbox)
    private val codex = CodexAgentLoop(sandbox, index, httpClient)

    private suspend fun backend(config: AppSettings): AssistantBackend =
        config.assistantBackend ?: withContext(Dispatchers.IO) {
            if (BuildConfig.PI_ENABLED && sandbox.supported) AssistantBackend.PI else AssistantBackend.BUILT_IN
        }

    internal var testRequestObserver: ((List<AgentMessage>) -> Unit)? = null
    private class SessionSlot {
        val state = MutableStateFlow(AssistantState())
        var job: Job? = null
    }

    private val global = SessionSlot()
    private val recordings = mutableMapOf<String, SessionSlot>()
    private val updates = MutableSharedFlow<AssistantUpdate>(extraBufferCapacity = 64)
    val events = updates.asSharedFlow()
    val state = global.state.asStateFlow()

    suspend fun load() {
        try { load(global, null) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { global.state.update { it.copy(error = settings.text(R.string.assistant_failed)) } }
    }

    fun stateFor(recordingId: String?): StateFlow<AssistantState> = slot(recordingId).state.asStateFlow()

    private fun slot(recordingId: String?): SessionSlot {
        if (recordingId == null) return global
        return recordings.getOrPut(recordingId) {
            SessionSlot().also { entry ->
                scope.launch {
                    try { load(entry, recordingId) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { entry.state.update { it.copy(error = settings.text(R.string.assistant_failed)) } }
                }
            }
        }
    }

    private suspend fun load(slot: SessionSlot, recordingId: String?) {
        val generation = index.corpusGeneration
        val session = (index.latestSession(recordingId) ?: AgentSession().also {
            index.writeAssistantBody(recordingId, generation, Json.encodeToJsonElement(it).jsonObject, reset = false)
        }).snapshot()
        slot.state.value = AssistantState(session = session, ready = true, contextUsage = session.lastUsage)
    }

    fun ask(question: String, recordingId: String? = null) {
        val slot = slot(recordingId)
        val current = slot.state.value
        if (!current.ready || current.busy || question.isBlank()) return
        val runId = UUID.randomUUID().toString()
        val sessionId = current.session.id
        fun publish(event: AssistantEvent) {
            slot.state.update { previous ->
                if (event !is AssistantEvent.Started && previous.runId != runId) return@update previous
                when (event) {
                    is AssistantEvent.Started -> previous.copy(runId = runId, busy = true,
                        pendingQuestion = event.question, error = null, draft = "", trace = emptyList(), cancelled = false)
                    is AssistantEvent.Draft -> previous.copy(draft = event.text)
                    is AssistantEvent.ToolProgress -> previous.copy(trace = previous.trace.toMutableList().apply {
                        if (event.step.id < size) this[event.step.id] = event.step else add(event.step)
                    })
                    is AssistantEvent.Usage -> previous.copy(contextUsage = event.usage)
                    is AssistantEvent.Completed -> previous.copy(session = event.session, runId = null, busy = false,
                        pendingQuestion = null, draft = "", trace = emptyList())
                    is AssistantEvent.Failed -> previous.copy(runId = null, busy = false, error = event.message, draft = "")
                    AssistantEvent.Cancelled -> previous.copy(runId = null, busy = false, cancelled = true, draft = "")
                }
            }
            updates.tryEmit(AssistantUpdate(sessionId, runId, event))
        }
        publish(AssistantEvent.Started(question))
        slot.job = scope.launch {
            try {
                val profile = settings.state.value
                val selected = backend(profile)
                val language = settings.outputLanguage()
                val workspace = NodeWorkspaceTool.requested(question)
                check(!workspace || selected != AssistantBackend.BUILT_IN) {
                    settings.text(R.string.assistant_node_requires_backend)
                }
                val bundled = when (selected) {
                    AssistantBackend.CODEX -> BuildConfig.CODEX_ENABLED
                    AssistantBackend.PI -> BuildConfig.PI_ENABLED
                    AssistantBackend.BUILT_IN -> true
                }
                check(bundled && (selected == AssistantBackend.BUILT_IN || withContext(Dispatchers.IO) { sandbox.supported })) {
                    settings.text(R.string.assistant_backend_unavailable)
                }
                val notes = AssistantModelSettings(profile.llm.baseUrl, profile.llm.model)
                val (config, keyName) = when {
                    selected == AssistantBackend.CODEX -> profile.codex to AssistantModelSettings.CODEX_KEY
                    selected == AssistantBackend.PI && profile.pi.baseUrl.isNotBlank() -> profile.pi to AssistantModelSettings.PI_KEY
                    else -> notes to Processor.KEY_LLM
                }
                require(config.baseUrl.isNotBlank() && config.model.isNotBlank()) {
                    settings.text(R.string.assistant_config_required)
                }
                val url = config.baseUrl.toHttpUrl()
                val apiKey = settings.secrets.get(keyName)
                val session = activeTurn.withLock {
                    if (selected == AssistantBackend.CODEX) {
                        require(url.isHttps || Processor.debugLoopback(url)) { settings.text(R.string.llm_https_required) }
                        codex.ask(recordingId, sessionId, question, language, url, config.model, apiKey, ::publish)
                    } else {
                        val test = BuildConfig.DEBUG && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
                        val model = HttpAgentModel(LlmConfig(url, config.model, allowInsecureForTests = test, outputLanguage = language),
                            apiKey, client = httpClient, onRequestMessages = testRequestObserver.takeIf { BuildConfig.DEBUG },
                            toolSchemas = if (workspace) NodeWorkspaceTool.schemas else AgentToolSchemas.json)
                        if (selected == AssistantBackend.PI) pi.command("ask", recordingId, index, sessionId,
                            question, language, model, ::publish)
                        else legacyAsk(recordingId, sessionId, question, language, model, ::publish)
                    }
                }
                publish(AssistantEvent.Completed(session))
            } catch (error: CancellationException) {
                publish(AssistantEvent.Cancelled)
                throw error
            } catch (error: Exception) {
                publish(AssistantEvent.Failed(error.message ?: settings.text(R.string.assistant_failed)))
            }
        }
    }

    private suspend fun legacyAsk(recordingId: String?, sessionId: String, question: String, language: String,
        model: AgentModel, publish: (AssistantEvent) -> Unit): AssistantSession {
        val generation = index.corpusGeneration
        val stored = index.readAssistantBody(recordingId, generation)
        val invalidated = stored.getValue("invalidated").jsonPrimitive.boolean
        val session = (index.latestSession(recordingId) ?: AgentSession(id = sessionId)).let {
            if (invalidated) it.copy(memory = "", firstKeptTurn = it.turns.size,
                contextMessages = emptyList(), contextInitialized = true, lastUsage = null) else it
        }
        val result = RecordingAgent(model, index.toolsFor(recordingId), outputLanguage = language,
            recordingId = recordingId).ask(session, question,
            onUsage = { publish(AssistantEvent.Usage(it)) }, onTrace = { publish(AssistantEvent.ToolProgress(it)) },
            onDraft = { publish(AssistantEvent.Draft(it)) })
        index.writeAssistantBody(recordingId, generation, Json.encodeToJsonElement(result.session).jsonObject, reset = false)
        return result.session.snapshot()
    }

    fun cancel(recordingId: String? = null, runId: String? = null) {
        val slot = slot(recordingId)
        if (runId == null || slot.state.value.runId == runId) slot.job?.cancel()
    }

    fun clearHistory(recordingId: String? = null) {
        val slot = slot(recordingId)
        if (!slot.state.value.ready || slot.state.value.busy) return
        slot.state.update { it.copy(busy = true) }
        scope.launch {
            try {
                val generation = index.corpusGeneration
                val empty = AgentSession()
                index.writeAssistantBody(recordingId, generation, Json.encodeToJsonElement(empty).jsonObject, reset = true)
                val session = empty.snapshot()
                slot.state.value = AssistantState(session = session, ready = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                slot.state.update { it.copy(busy = false, error = settings.text(R.string.assistant_failed)) }
            }
        }
    }

    fun recordingDeleted(recordingId: String) {
        recordings.remove(recordingId)?.job?.cancel()
        global.job?.cancel()
    }
}
