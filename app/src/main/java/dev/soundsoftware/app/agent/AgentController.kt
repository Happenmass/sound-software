package dev.soundsoftware.app.agent

import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.LlmConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
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
    private val runtime: PiAgentLoop? = null,
) {
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
        val session = runtime?.command("open", recordingId, index)
            ?: (index.latestSession(recordingId) ?: AgentSession()).snapshot()
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
                val config = settings.state.value.llm
                require(config.baseUrl.isNotBlank() && config.model.isNotBlank()) {
                    settings.text(R.string.assistant_config_required)
                }
                val url = config.baseUrl.toHttpUrl()
                val test = BuildConfig.DEBUG && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
                val language = settings.outputLanguage()
                val model = HttpAgentModel(LlmConfig(url, config.model, allowInsecureForTests = test, outputLanguage = language),
                    settings.secrets.get(Processor.KEY_LLM), onRequestMessages = testRequestObserver.takeIf { BuildConfig.DEBUG })
                val session = if (runtime != null) runtime.command("ask", recordingId, index, sessionId,
                    question, language, model, ::publish) else legacyAsk(recordingId, sessionId, question, language, model, ::publish)
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
        val session = index.latestSession(recordingId) ?: AgentSession(id = sessionId)
        val result = RecordingAgent(model, index.toolsFor(recordingId), outputLanguage = language,
            recordingId = recordingId).ask(session, question,
            onUsage = { publish(AssistantEvent.Usage(it)) }, onTrace = { publish(AssistantEvent.ToolProgress(it)) },
            onDraft = { publish(AssistantEvent.Draft(it)) })
        index.saveSession(result.session, recordingId, generation.takeIf { recordingId == null })
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
                val session = if (runtime != null) runtime.command("clear", recordingId, index) else {
                    index.clearSessions(recordingId)
                    AssistantSession()
                }
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
