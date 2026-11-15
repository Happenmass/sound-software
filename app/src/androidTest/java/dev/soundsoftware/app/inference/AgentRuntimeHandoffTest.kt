package dev.soundsoftware.app.inference

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.agent.AgentSandbox
import dev.soundsoftware.app.data.AssistantBackend
import dev.soundsoftware.app.data.AssistantModelSettings
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real native runtimes and app controller, with local synthetic model responses. */
class AgentRuntimeHandoffTest {
    private val wire = Json { encodeDefaults = true }
    @Test fun compressedHistoryToolsUsageAndQueuedCancellationSurviveBackendHandoff() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SoundApplication
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentPhone") == "true")
        check((app.packageName.endsWith(".agentcleanup") || app.packageName.endsWith(".agentcleanup.mcppoc")) && app.instrumentationDataDir != null)
        assertTrue(AgentSandbox(app).supported)
        withTimeout(10000) { app.assistant.state.first { it.ready } }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val id = UUID.randomUUID().toString()
            val record = Recording(id, 0, 2000, title = "Fixture", stage = ProcessingStage.DONE,
                transcript = Transcript(listOf(Segment("s", "Larkspur moved to Thursday.", 0, 1000)), "fixture", "fixture", "SEGMENT", 0))
            withContext(Dispatchers.IO) { PcmWav.writeMono(app.repository.audioFile(id), FloatArray(32000), 16000); app.repository.add(record) }
            val initial = AgentSession(turns = listOf(AgentTurn("discarded-question", "old answer")),
                memory = "retained-summary", contextInitialized = true, firstKeptTurn = 1, compactions = 1)
            app.recordingIndex.writeAssistantBody(null, app.recordingIndex.corpusGeneration, wire.encodeToJsonElement(initial).jsonObject, true)
            withContext(Dispatchers.Main) { app.assistant.load() }
            MockWebServer().use { pi -> MockWebServer().use { codex ->
                pi.dispatcher = AgentBackendPhoneTest.ModelFixture(false, "Larkspur")
                codex.dispatcher = AgentBackendPhoneTest.ModelFixture(true, "Larkspur")
                pi.start(); codex.start()
                app.settings.update { it.copy(pi = AssistantModelSettings(pi.url("/v1").newBuilder().host("localhost").build().toString(), "fixture"),
                    codex = AssistantModelSettings(codex.url("/v1").newBuilder().host("localhost").build().toString(), "gpt-5.4")) }
                suspend fun ask(backend: AssistantBackend, question: String, count: Int) {
                    app.settings.update { it.copy(assistantBackend = backend) }
                    withContext(Dispatchers.Main) { app.assistant.ask(question) }
                    val state = withTimeout(30000) { app.assistant.state.first { !it.busy } }
                    assertNull(state.error, state.error); assertEquals(count, state.session.turns.size)
                    assertEquals(initial.id, state.session.id); assertEquals(id, state.session.turns.last().citations.single().recordingId)
                    assertNull(app.recordingIndex.interruptedQuestion(null))
                }
                ask(AssistantBackend.PI, "first question", 2)
                pi.takeRequest(3, TimeUnit.SECONDS); pi.takeRequest(3, TimeUnit.SECONDS)
                val piSaved = app.recordingIndex.latestSession()!!
                app.recordingIndex.writeAssistantBody(null, app.recordingIndex.corpusGeneration,
                    wire.encodeToJsonElement(piSaved.copy(contextMessages = (0..7).map { AgentMessage("user", "older-$it") } + piSaved.contextMessages,
                        lastUsage = AgentUsage(199900, 100))).jsonObject, false)
                ask(AssistantBackend.CODEX, "second question", 3)
                val summaryRequest = codex.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
                assertTrue(summaryRequest.contains("previousSummary")); assertFalse(summaryRequest.contains("discarded-question"))
                val codexRequest = codex.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
                codex.takeRequest(3, TimeUnit.SECONDS)
                assertTrue(codexRequest.contains("first question")); assertTrue(codexRequest.contains("retained-summary"))
                assertFalse(codexRequest.contains("discarded-question"))
                val saved = app.recordingIndex.latestSession()!!
                assertEquals(2, saved.compactions)
                assertEquals("retained-summary after compaction", saved.memory)
                assertEquals(28L, saved.lastUsage?.totalTokens)
                assertTrue(saved.contextMessages.any { it.role == "tool" && it.content.contains("Larkspur") })
                ask(AssistantBackend.PI, "third question", 4)
                val piRequest = pi.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
                pi.takeRequest(3, TimeUnit.SECONDS)
                assertTrue(piRequest.contains("second question")); assertTrue(piRequest.contains("retained-summary"))
                assertFalse(piRequest.contains("discarded-question"))
                assertEquals(initial.turns.first(), app.recordingIndex.latestSession()!!.turns.first())
                app.settings.update { it.copy(assistantBackend = AssistantBackend.CODEX) }
                withContext(Dispatchers.Main) { app.assistant.ask("Cancel live fixture global") }
                assertNotNull(withContext(Dispatchers.IO) { codex.takeRequest(5, TimeUnit.SECONDS) })
                withTimeout(5000) { app.assistant.busy.first { it } }
                withTimeout(5000) { while (!app.workServiceActive) delay(20) }
                assertEquals("Cancel live fixture global", app.recordingIndex.interruptedQuestion(null))
                // A second recording scope queues behind the global request and keeps work alive.
                val local = withContext(Dispatchers.Main) { app.assistant.stateFor(id) }
                withTimeout(5000) { local.first { it.ready } }
                withContext(Dispatchers.Main) { app.assistant.ask("Cancel live fixture local", id) }
                withTimeout(5000) { while (app.recordingIndex.interruptedQuestion(id) == null) delay(20) }
                activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertTrue(app.workServiceActive)
                withContext(Dispatchers.Main) { app.assistant.cancel() }
                withTimeout(5000) { app.assistant.state.first { it.cancelled } }
                assertTrue(app.assistant.busy.value)
                assertNotNull(withContext(Dispatchers.IO) { codex.takeRequest(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { app.assistant.cancel(id) }
                withTimeout(5000) { app.assistant.busy.first { !it } }
                assertNull(app.recordingIndex.interruptedQuestion(null)); assertNull(app.recordingIndex.interruptedQuestion(id))
                assertEquals(4, app.recordingIndex.latestSession()!!.turns.size)
                assertTrue(app.recordingIndex.latestSession(id)!!.turns.isEmpty())
                withTimeout(5000) { while (app.workServiceActive) delay(20) }
            } }
        }
    }

    @Test fun nativeMcpDiscoversFiveToolsAndRejectsCrossRecordingQueries() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SoundApplication
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentPhone") == "true")
        check(app.packageName.endsWith(".agentcleanup") || app.packageName.endsWith(".agentcleanup.mcppoc"))
        assumeTrue(AgentSandbox(app).recordingMcpEnabled)
        ActivityScenario.launch(MainActivity::class.java).use {
            val keyword = "ScopeFixture"
            fun record(id: String, label: String) = Recording(id, 0, 1000, stage = ProcessingStage.DONE,
                transcript = Transcript(listOf(Segment("s", "$keyword recording $label authorized text", 0, 1000)), "fixture", "fixture", "SEGMENT", 0))
            val a = record(UUID.randomUUID().toString(), "A"); val b = record(UUID.randomUUID().toString(), "B")
            withContext(Dispatchers.IO) {
                for (recording in listOf(a, b)) {
                    PcmWav.writeMono(app.repository.audioFile(recording.id), FloatArray(16000), 16000)
                    app.repository.add(recording)
                }
            }
            val local = withContext(Dispatchers.Main) { app.assistant.stateFor(a.id) }
            val opened = withTimeout(10000) { local.first { it.ready || it.error != null } }
            assertTrue(opened.error, opened.ready)
            for (backend in AssistantBackend.entries) {
                val fixture = AgentBackendPhoneTest.ScopedMcpFixture(a, b, keyword)
                MockWebServer().use { server ->
                    server.dispatcher = AgentBackendPhoneTest.ModelFixture(backend == AssistantBackend.CODEX, scoped = fixture)
                    server.start()
                    val config = AssistantModelSettings(server.url("/v1").newBuilder().host("localhost").build().toString(), "gpt-5.4")
                    app.settings.update { it.copy(assistantBackend = backend, pi = config, codex = config) }
                    withContext(Dispatchers.Main) { app.assistant.ask("Check the scoped recordings", a.id) }
                    val result = withTimeout(30000) { local.first { !it.busy } }
                    fixture.failure?.let { throw AssertionError("Synthetic MCP responder failed", it) }
                    assertNull(result.error, result.error)
                    assertTrue(fixture.denied); assertEquals(5, fixture.discovered.size)
                    assertEquals(a.id, result.session.turns.last().citations.single().recordingId)
                    assertTrue(app.recordingIndex.latestSession(a.id)!!.contextMessages.any { message ->
                        message.role == "tool" && message.content.contains("recording_scope_denied") })
                }
            }
        }
    }
}
