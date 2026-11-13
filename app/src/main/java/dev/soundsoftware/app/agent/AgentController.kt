package dev.soundsoftware.app.agent

import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.LlmConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One shared assistant session for all entry points; cancelled work never commits a partial answer. */
data class AssistantState(val session: AgentSession = AgentSession(), val ready: Boolean = false, val indexed: Int = 0,
    val busy: Boolean = false, val step: String? = null, val error: String? = null, val contextUsage: AgentUsage? = null,
    val pendingQuestion: String? = null, val trace: List<AgentTraceStep> = emptyList())
class AgentController(private val index: RecordingIndex, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(AssistantState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    suspend fun load() {
        val session=index.latestSession() ?: AgentSession()
        mutable.update { it.copy(session=session,ready=true,indexed=index.count(),contextUsage=session.lastUsage) }
    }
    fun ask(question: String) {
        if(!mutable.value.ready || mutable.value.busy || question.isBlank()) return
        val session=mutable.value.session
        mutable.update {it.copy(busy=true,error=null,step=settings.text(R.string.preparing_search),pendingQuestion=question,trace=emptyList())}
        job=scope.launch {
            try {
                val config=settings.state.value.llm
                require(config.baseUrl.isNotBlank() && config.model.isNotBlank()) { settings.text(R.string.assistant_config_required) }
                val url=config.baseUrl.toHttpUrl()
                val test=BuildConfig.DEBUG && url.host in setOf("localhost","127.0.0.1","10.0.2.2")
                val outputLanguage = settings.outputLanguage()
                val model=HttpAgentModel(LlmConfig(url,config.model,allowInsecureForTests=test, outputLanguage = outputLanguage),settings.secrets.get(Processor.KEY_LLM))
                val result=RecordingAgent(model, index, outputLanguage = outputLanguage).ask(session,question,onUsage={usage->mutable.update {it.copy(contextUsage=usage)}},
                    onTrace={event->mutable.update {state->state.copy(trace=state.trace.toMutableList().apply {
                        if(event.id<size)this[event.id]=event else add(event)
                    })}}) { step -> mutable.update {it.copy(step=step)} }
                ensureActive()
                index.saveSession(result.session)
                mutable.update {it.copy(session=result.session,indexed=index.count(),pendingQuestion=null,trace=emptyList())}
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {mutable.update {it.copy(error=e.message ?: settings.text(R.string.assistant_failed))}}
            finally {mutable.update {it.copy(busy=false,step=null)}}
        }
    }
    fun cancel() {job?.cancel()}
    fun newSession() {
        if(mutable.value.busy)return
        mutable.update { it.copy(busy=true) }
        job=scope.launch {
            try { val next=AgentSession(); index.saveSession(next);mutable.update {it.copy(session=next,error=null,contextUsage=null,pendingQuestion=null,trace=emptyList())} }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun clearHistory() {
        if(mutable.value.busy)return
        mutable.update { it.copy(busy=true) }
        job=scope.launch {
            try { index.clearSessions();mutable.update {it.copy(session=AgentSession(),error=null,contextUsage=null,pendingQuestion=null,trace=emptyList())} }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
}
