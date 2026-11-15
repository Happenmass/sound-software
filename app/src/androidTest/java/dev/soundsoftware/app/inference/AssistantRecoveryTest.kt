package dev.soundsoftware.app.inference

import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.R
import dev.soundsoftware.app.agent.*
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.model.Recording
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantRecoveryTest {
    private val wire = Json { encodeDefaults = true }
    @Test fun interruptedRunReopensWithQuestionButNeverAutomaticallyResumes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recovery-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val file = File(directory, "index.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val session = AgentSession(turns = listOf(AgentTurn("completed", "saved")))
            RecordingIndex(file).use { index ->
                index.saveSession(session)
                index.beginAssistantRun(null, session.id, "run", "unfinished question")
                // A Pi initialization write is not a completed answer.
                index.writeAssistantBody(null, index.corpusGeneration, wire.encodeToJsonElement(session.initializeContext()).jsonObject, false)
                assertEquals("unfinished question", index.interruptedQuestion(null))
            }
            RecordingIndex(file).use { index ->
                val settings = SettingsStore(context, "$name-")
                val controller = AgentController(index, settings, scope, AgentSandbox(context, directory))
                controller.load()
                assertEquals("unfinished question", controller.state.value.pendingQuestion)
                assertEquals(settings.text(R.string.assistant_interrupted), controller.state.value.error)
                assertFalse(controller.state.value.busy); assertFalse(controller.busy.value)
                assertEquals(session.turns, controller.state.value.session.turns)
                val next = session.completeTurn(AgentTurn("unfinished question", "done"), emptyList(), AgentUsage(10, 2))
                index.writeAssistantBody(null, index.corpusGeneration, wire.encodeToJsonElement(next).jsonObject, false)
                assertNull(index.interruptedQuestion(null))
                controller.load(); assertNull(controller.state.value.error)
                index.beginAssistantRun(null, session.id, "old-run", "old")
                index.beginAssistantRun(null, session.id, "new-run", "new")
                index.finishAssistantRun("old-run")
                assertEquals("new", index.interruptedQuestion(null))
                index.writeAssistantBody(null, index.corpusGeneration, wire.encodeToJsonElement(AgentSession()).jsonObject, true)
                assertNull(index.interruptedQuestion(null))
                index.upsert(Recording("local", 0, 0))
                index.saveSession(session, "local")
                index.beginAssistantRun("local", session.id, "local-run", "local question")
                index.remove("local")
                assertNull(index.interruptedQuestion("local"))
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
