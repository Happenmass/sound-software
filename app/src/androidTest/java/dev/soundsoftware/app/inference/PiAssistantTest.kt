package dev.soundsoftware.app.inference

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.R
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.agent.AssistantEvent
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.ProcessingStage
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.Segment
import dev.soundsoftware.core.model.Transcript
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PiAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun piLooksUpTwoLocalRecordingsAndCitationOpensPlayback() {
        assumeTrue(BuildConfig.PI_ENABLED)
        val app = compose.activity.application as SoundApplication
        // Use the side-by-side build so this test cannot replace real settings or history.
        assumeTrue(app.packageName.endsWith(".pipoc"))
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val fixtures = listOf(
            recording("Pi Fixture A", "Larkspur launch was first scheduled for Monday.", 2200),
            recording("Pi Fixture B", "Larkspur launch moved to Thursday.", 5300),
        )
        runBlocking {
            for (recording in fixtures) {
                PcmWav.writeMono(app.repository.audioFile(recording.id), FloatArray(16_000 * 18), 16_000)
                app.repository.add(recording)
            }
        }
        val previous = app.settings.state.value.llm
        val question = "When did the Larkspur launch move?"
        val waiting = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val messages = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray
                    val reply = messages.last().jsonObject
                    val role = reply.getValue("role").jsonPrimitive.content
                    if (reply["content"]?.jsonPrimitive?.contentOrNull == "Cancel this test question") {
                        waiting.countDown()
                        return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                    }
                    if (role != "tool") return completion(buildJsonObject {
                        put("content", JsonNull)
                        putJsonArray("tool_calls") {
                            addJsonObject {
                                put("id", "call_larkspur")
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", "search_recordings")
                                    put("arguments", """{"query":"Larkspur"}""")
                                }
                            }
                        }
                    }, "tool_calls")

                    val passages = Json.parseToJsonElement(reply.getValue("content").jsonPrimitive.content)
                        .jsonObject.getValue("evidence").jsonArray
                    val citations = passages.map { passage ->
                        JsonObject(passage.jsonObject.filterKeys { it in setOf("recordingId", "segmentId", "revision", "quote") })
                    }
                    return completion(buildJsonObject {
                        put("content", buildJsonObject {
                            put("found", citations.isNotEmpty())
                            put("answer", "The Larkspur launch moved from Monday to Thursday.")
                            put("citations", JsonArray(citations))
                        }.toString())
                    }, "stop")
                }
            }
            server.start()
            app.settings.update { it.copy(llm = LlmSettings("http://localhost:${server.port}/v1", "fixture")) }
            try {
                val activity = compose.activity
                val assistant = activity.getString(R.string.recording_assistant)
                compose.onAllNodes(hasClickAction() and hasContentDescription(assistant)).onFirst().performClick()
                val placeholder = activity.getString(R.string.ask_recordings_placeholder)
                fun ask(text: String) {
                    compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true)).performTextInput(text)
                    compose.onNodeWithContentDescription(activity.getString(R.string.send)).performClick()
                }
                val initialTurns = app.assistant.state.value.session.turns.size
                ask("Cancel this test question")
                assertTrue(waiting.await(15, TimeUnit.SECONDS))
                compose.onNodeWithText(activity.getString(R.string.stop)).performClick()
                compose.waitUntil(5_000) { !app.assistant.state.value.busy }
                assertEquals(initialTurns, app.assistant.state.value.session.turns.size)
                ask(question)
                compose.waitUntil(35_000) {
                    val state = app.assistant.state.value
                    !state.busy && (state.error != null || state.session.turns.size > initialTurns)
                }
                val state = app.assistant.state.value
                assertNull(state.error)
                val turn = state.session.turns.last()
                assertEquals(question, turn.question)
                assertEquals(fixtures.map { it.id }.toSet(), turn.citations.map { it.recordingId }.toSet())
                assertTrue(turn.trace.any { it.toolName == "search_recordings" && it.status == "done" })
                assertEquals(3, server.requestCount)
                compose.onNodeWithText("Pi Fixture B · 00:05", substring = true).performClick()
                compose.waitUntil(5_000) {
                    val playback = app.playback.state.value
                    playback.recordingId == fixtures.last().id && playback.positionMs >= 5300
                }
                assertEquals(fixtures.last().id, app.playback.state.value.recordingId)
            } finally {
                app.settings.update { it.copy(llm = previous) }
                runBlocking { fixtures.forEach { app.repository.delete(it.id) } }
            }
        }
    }

    @Test
    fun configuredModelStreamsToolsAndKeepsRecordingSessionsSeparate() = runBlocking<Unit> {
        assumeTrue(BuildConfig.PI_ENABLED)
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("realPi") == "true")
        val app = compose.activity.application as SoundApplication
        check(app.instrumentationDataDir != null && app.repository.audioDir.parentFile == app.instrumentationDataDir) {
            "The real-model test must use its isolated fixture library"
        }
        val config = app.settings.state.value.llm
        check(config.baseUrl.startsWith("https://") && config.model.isNotBlank()) { "Configure an HTTPS model first" }
        check(app.settings.secrets.has(Processor.KEY_LLM)) { "The configured model requires a test credential" }
        compose.waitUntil(15_000) { app.assistant.state.value.ready }
        check(app.repository.recordings.value.isEmpty())
        val oldTurns = app.assistant.state.value.session.turns.size
        val fixtures = listOf(
            recording("Juniper Orbital Alpha", "Juniper Orbital launch was originally Monday.", 2400),
            recording("Juniper Orbital Beta", "Juniper Orbital launch moved to Thursday.", 5300),
        )
        for (recording in fixtures) {
            PcmWav.writeMono(app.repository.audioFile(recording.id), FloatArray(16_000 * 18), 16_000)
            app.repository.add(recording)
        }
        val events = java.util.concurrent.CopyOnWriteArrayList<dev.soundsoftware.app.agent.AssistantUpdate>()
        val listener = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { app.assistant.events.collect { events.add(it) } }
        try {
            val activity = compose.activity
            fun ask(text: String) {
                val placeholder = activity.getString(R.string.ask_recordings_placeholder)
                compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true)).performTextInput(text)
                compose.onNodeWithContentDescription(activity.getString(R.string.send)).performClick()
            }
            val assistant = activity.getString(R.string.recording_assistant)
            compose.onAllNodes(hasClickAction() and hasContentDescription(assistant)).onFirst().performClick()
            val question = "Search for Juniper Orbital. Compare the original and updated launch days. Cite both recordings."
            ask(question)
            compose.waitUntil(100_000) {
                val state = app.assistant.state.value
                !state.busy && (state.error != null || state.session.turns.size > oldTurns)
            }
            val global = app.assistant.state.value
            assertNull(global.error)
            val turn = global.session.turns.last()
            assertEquals(question, turn.question)
            assertEquals(fixtures.map { it.id }.toSet(), turn.citations.map(RecordingCitation::recordingId).toSet())
            val globalEvents = events.filter { it.sessionId == global.session.id }
            assertTrue(globalEvents.any { (it.event as? AssistantEvent.ToolProgress)?.step?.toolName != null })
            val drafts = globalEvents.mapNotNull { (it.event as? AssistantEvent.Draft)?.text }.filter { it.isNotEmpty() }
            assertTrue("Expected multiple distinct streamed draft states", drafts.distinct().size >= 2)
            assertTrue(globalEvents.indexOfFirst { (it.event as? AssistantEvent.Draft)?.text?.isNotEmpty() == true } <
                globalEvents.indexOfFirst { it.event is AssistantEvent.Completed })
            compose.onAllNodesWithText(activity.getString(R.string.expand)).onLast().performScrollTo().performClick()
            val toolName = turn.trace.first { it.toolName != null }.toolName!!
            compose.onAllNodesWithText(toolName, substring = true).onFirst().assertExists()
            compose.onNodeWithText(activity.getString(R.string.collapse)).performScrollTo().performClick()
            val cited = turn.citations.first { it.recordingId == fixtures.last().id }
            assertEquals(5300L, cited.startMs)
            compose.onNodeWithText("Juniper Orbital Beta · 00:05", substring = true)
                .performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(8_000) { app.playback.state.value.let {
                it.recordingId == fixtures.last().id && it.playing && it.positionMs in 5_200..8_000
            } }

            compose.onNodeWithText(activity.getString(R.string.ai_qa)).performClick()
            val local = withContext(Dispatchers.Main) { app.assistant.stateFor(fixtures.last().id) }
            compose.waitUntil(8_000) { local.value.ready }
            assertTrue(local.value.session.turns.isEmpty())
            assertNotEquals(global.session.id, local.value.session.id)
            ask("Search for Juniper Orbital. What launch day is stated in this recording? Cite the original sentence.")
            compose.waitUntil(100_000) { !local.value.busy && (local.value.error != null || local.value.session.turns.isNotEmpty()) }
            assertNull(local.value.error)
            assertEquals(setOf(fixtures.last().id), local.value.session.turns.single().citations.map(RecordingCitation::recordingId).toSet())

            ask("Search for Juniper Orbital and cite the launch day. Write a detailed 40-item explanation of what the transcript confirms and what remains unknown.")
            compose.waitUntil(45_000) {
                val runId = local.value.runId
                runId != null && local.value.draft.isNotEmpty()
            }
            compose.onNodeWithText(activity.getString(R.string.stop)).performClick()
            compose.waitUntil(8_000) { local.value.cancelled }
            assertEquals(1, local.value.session.turns.size)
            assertEquals(oldTurns + 1, app.assistant.state.value.session.turns.size)

            app.repository.delete(fixtures.last().id)
            assertNull(app.recordingIndex.latestSession(fixtures.last().id))
            val preserved = app.recordingIndex.latestSession()!!
            assertEquals(turn.answer, preserved.turns.last().answer)
            assertTrue(app.recordingIndex.readAssistantBody(null, app.recordingIndex.corpusGeneration).getValue("invalidated").jsonPrimitive.boolean)
            assertNull(app.recordingIndex.resolve(turn.citations.first { it.recordingId == fixtures.last().id }))
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText(activity.getString(R.string.source_changed)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(turn.answer).assertExists()
            android.util.Log.i("PiAssistantTest", "Real provider: ${drafts.distinct().size} draft updates; tools, playback, cancellation and deletion passed")
        } finally {
            listener.cancelAndJoin()
            withContext(Dispatchers.Main) {
                app.assistant.cancel()
                app.assistant.cancel(fixtures.last().id)
                app.playback.release()
            }
            fixtures.forEach { app.repository.delete(it.id) }
        }
    }

    @Test
    fun realModelResumesMigratedHistoryAndClearsItsContext() = runBlocking<Unit> {
        assumeTrue(BuildConfig.PI_ENABLED)
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("realPi") == "true")
        val app = compose.activity.application as SoundApplication
        check(app.instrumentationDataDir != null && app.repository.audioDir.parentFile == app.instrumentationDataDir)
        check(app.settings.state.value.llm.baseUrl.startsWith("https://") && app.settings.secrets.has(Processor.KEY_LLM))
        compose.waitUntil(15_000) { app.assistant.state.value.ready }
        check(app.repository.recordings.value.isEmpty())

        val alias = "Aurora-" + UUID.randomUUID().toString().take(8)
        val fixture = recording("Tamarind launch", "Tamarind launch is scheduled for Thursday.", 5300)
        PcmWav.writeMono(app.repository.audioFile(fixture.id), FloatArray(16_000 * 18), 16_000)
        app.repository.add(fixture)
        val previous = AgentSession(
            turns = listOf(AgentTurn("When is Tamarind launch?", "Thursday.")),
            contextInitialized = true,
            contextMessages = listOf(
                AgentMessage("user", "Tamarind is the search keyword. My conversation-only label is $alias. When is launch?"),
                AgentMessage("assistant", """{"found":true,"answer":"Tamarind launches Thursday.","citations":[]}"""),
            ),
        )
        app.recordingIndex.clearSessions()
        app.recordingIndex.saveSession(previous)
        val global = withContext(Dispatchers.Main) {
            app.assistant.load()
            app.assistant.state
        }
        val localHistory = AgentSession(turns = listOf(AgentTurn("Local fixture history", "Keep this separate.")))
        app.recordingIndex.saveSession(localHistory, fixture.id)
        val requests = java.util.concurrent.CopyOnWriteArrayList<List<AgentMessage>>()
        app.assistant.testRequestObserver = { requests.add(it.toList()) }
        try {
            assertEquals(previous.id, global.value.session.id)
            assertEquals(previous.turns, global.value.session.turns)
            val generation = app.recordingIndex.corpusGeneration
            suspend fun body(id: String? = null) = app.recordingIndex.readAssistantBody(id, generation).getValue("body").jsonObject
            assertEquals(1, body().getValue("hostVersion").jsonPrimitive.int)

            val activity = compose.activity
            val assistant = activity.getString(R.string.recording_assistant)
            compose.onAllNodes(hasClickAction() and hasContentDescription(assistant)).onFirst().performClick()
            val followUp = "Using the earlier search keyword, recheck the launch day in the transcript and cite it. Append my conversation-only label exactly if I previously provided one. If no earlier search keyword is available, report that you need one."
            fun ask() {
                val placeholder = activity.getString(R.string.ask_recordings_placeholder)
                compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true)).performTextInput(followUp)
                compose.onNodeWithContentDescription(activity.getString(R.string.send)).performClick()
            }
            ask()
            compose.waitUntil(90_000) { !global.value.busy && (global.value.error != null || global.value.session.turns.size == 2) }
            assertNull(global.value.error)
            val continued = global.value.session.turns.last()
            assertTrue(continued.answer.contains(alias))
            assertEquals(fixture.id, continued.citations.single().recordingId)
            assertTrue(requests.first().any { alias in it.content })
            assertFalse(requests.flatten().any { "Local fixture history" in it.content })

            compose.onNodeWithText(activity.getString(R.string.clear_history)).performClick()
            compose.onNodeWithText(activity.getString(R.string.clear), substring = false).performClick()
            compose.waitUntil(15_000) { !global.value.busy && global.value.session.turns.isEmpty() }
            assertNotEquals(previous.id, global.value.session.id)
            val cleared = body()
            assertTrue(cleared.getValue("contextMessages").jsonArray.isEmpty())
            assertEquals("", cleared.getValue("memory").jsonPrimitive.content)
            assertEquals(localHistory.id, body(fixture.id).getValue("id").jsonPrimitive.content)

            requests.clear()
            ask()
            compose.waitUntil(90_000) { !global.value.busy && (global.value.error != null || global.value.session.turns.size == 1) }
            assertNull(global.value.error)
            assertFalse(global.value.session.turns.single().answer.contains(alias))
            val messages = body().getValue("contextMessages").jsonArray
            assertEquals(followUp, messages.first().jsonObject.getValue("content").jsonPrimitive.content)
            assertFalse(messages.toString().contains(alias))
            assertEquals(listOf("system", "user"), requests.first().map(AgentMessage::role))
            assertEquals(followUp, requests.first().last().content)
            assertFalse(requests.flatten().any { alias in it.content })
            assertEquals(localHistory.turns, app.recordingIndex.latestSession(fixture.id)?.turns)
        } finally {
            app.assistant.testRequestObserver = null
            app.repository.delete(fixture.id)
        }
    }

    private fun recording(title: String, text: String, start: Long) = Recording(
        id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(), durationMs = 18_000,
        title = title, stage = ProcessingStage.DONE,
        transcript = Transcript(listOf(Segment("fixture", text, start, start + 1_000)), "fixture", "test", "SEGMENT", 0),
    )

    private fun completion(message: JsonObject, finish: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
            putJsonArray("choices") { addJsonObject { put("message", message); put("finish_reason", finish) } }
            putJsonObject("usage") { put("prompt_tokens", 120); put("completion_tokens", 30); put("total_tokens", 150) }
        }.toString())
}
