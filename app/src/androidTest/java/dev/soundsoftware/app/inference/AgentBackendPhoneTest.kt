package dev.soundsoftware.app.inference

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.R
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.agent.AgentSandbox
import dev.soundsoftware.app.agent.RecordingMcpBridge
import dev.soundsoftware.app.data.AssistantBackend
import dev.soundsoftware.app.data.AssistantModelSettings
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.ProcessingStage
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.Segment
import dev.soundsoftware.core.model.Transcript
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AgentBackendPhoneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val activity get() = compose.activity
    private val app get() = activity.application as SoundApplication
    private val sandbox get() = AgentSandbox(app, app.instrumentationDataDir ?: app.filesDir)

    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun requirePhoneProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentPhone") == "true")
        check(app.packageName.endsWith(".agentpoc") || app.packageName.endsWith(".agentcleanup") || app.packageName.endsWith(".release") && app.instrumentationDataDir != null) {
            "Use a side-by-side build or an isolated release fixture."
        }
        assertTrue("The phone did not support the installed sandbox launcher", sandbox.supported)
    }

    @Test fun stdioMcpDiscoversFiveToolsAndRejectsAnotherRecording() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentPhone") == "true")
        check(app.packageName.endsWith(".mcppoc") && app.instrumentationDataDir != null)
        assertTrue(sandbox.supported)
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val stamp = UUID.randomUUID().toString().take(8)
        val keyword = "Beacon$stamp"
        val (a, b) = addMcpRecordings(stamp, keyword)
        listOf("Pi", "Codex").forEachIndexed { index, backend ->
            val scoped = ScopedMcpFixture(a, b, keyword)
            MockWebServer().use { server ->
                server.dispatcher = ModelFixture(responses = backend == "Codex", scoped = scoped)
                server.start()
                configure(backend, "http://localhost:${server.port}/v1",
                    if (backend == "Pi") "fixture-pi-model" else "gpt-5.4", "fixture-key")
                openMcpRecording(a)
                val state = app.assistant.stateFor(a.id)
                compose.waitUntil(10_000) { state.value.ready }
                submitInAssistant("Check $keyword using recording tools for the current recording.")
                compose.waitUntil(35_000) { scoped.failure != null || state.value.error != null || state.value.session.turns.size > index }
                scoped.failure?.let { throw AssertionError("$backend model fixture rejected the exchange", it) }
                assertNull(state.value.error)
                val turn = state.value.session.turns.last()
                assertEquals(listOf(a.id), turn.citations.map { it.recordingId })
                assertTrue(scoped.denied)
                val report = android.os.Bundle().apply {
                    putString("stream", "\n$backend MCP: ${scoped.discovered.sorted().joinToString()}, A evidence verified, B rejected by Android\n")
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
                if (index == 0) compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
            }
        }
    }

    @Test fun realModelsUseScopedStdioRecordingMcp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("agentPhone") == "true" && args.getString("liveModel") == "true")
        check(app.packageName.endsWith(".mcppoc") || app.packageName.endsWith(".release"))
        check(app.instrumentationDataDir != null && app.instrumentationIsolateSettings && sandbox.supported)
        val base = requireNotNull(args.getString("liveBase"))
        val model = requireNotNull(args.getString("liveModelName"))
        val keyFile = File(app.cacheDir, "agent-live-key")
        val key = keyFile.takeIf { it.isFile }?.let { it.readText().trim().also { _ -> it.delete() } }
            ?: requireNotNull(args.getString("liveKey")) { "Provide the temporary model gateway key." }
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        assertTrue("Live MCP requires a fresh synthetic recording library", app.repository.recordings.value.isEmpty())
        val stamp = UUID.randomUUID().toString().take(8)
        val keyword = "Larkspur"
        val (a, b) = addMcpRecordings(stamp, keyword)
        val previous = app.settings.state.value
        val keys = listOf(AssistantModelSettings.PI_KEY, AssistantModelSettings.CODEX_KEY)
        val previousKeys = keys.associateWith(app.settings.secrets::get)
        val catalogs = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val expected = setOf("list_highlights", "list_recordings", "list_todos", "read_segments", "search_recordings")
        try {
            RecordingMcpBridge.testCatalogObserver = { catalog ->
                catalogs += catalog.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }.toSet()
            }
            listOf("Pi", "Codex").forEachIndexed { index, backend ->
                configure(backend, base, model, key)
                openMcpRecording(a)
                val state = app.assistant.stateFor(a.id)
                compose.waitUntil(10_000) { state.value.ready }
                if (index > 0) {
                    val previousSession = state.value.session.id
                    compose.runOnIdle { app.assistant.clearHistory(a.id) }
                    compose.waitUntil(10_000) { !state.value.busy && state.value.session.id != previousSession }
                }
                fun ask(question: String): dev.soundsoftware.core.agent.AgentTurn {
                    val count = state.value.session.turns.size
                    submitInAssistant(question)
                    compose.waitUntil(100_000) { !state.value.busy && (state.value.error != null || state.value.session.turns.size > count) }
                    assertNull("$backend reported ${state.value.error}", state.value.error)
                    return state.value.session.turns.last()
                }

                val turn = ask("Use search_recordings in the current synthetic recording with query exactly '$keyword'. " +
                    "Use that single keyword and omit optional filters or set them to null. Quote and cite the passage returned by the tool.")
                val aResult = turn.trace.firstOrNull { it.toolName == "search_recordings" && it.status == "done" }
                assertNotNull("$backend did not execute search_recordings: ${turn.trace}", aResult)
                val passages = Json.parseToJsonElement(checkNotNull(aResult?.result)).jsonObject.getValue("evidence").jsonArray
                assertEquals("$backend search arguments: ${aResult?.arguments}", listOf(a.id),
                    passages.map { it.jsonObject.getValue("recordingId").jsonPrimitive.content })
                assertTrue("$backend did not return an Android-verified A citation", turn.citations.isNotEmpty())
                assertTrue(turn.citations.all { it.recordingId == a.id })

                val listedOnSearch = catalogs.toList()
                assertTrue("$backend did not request the MCP catalog", listedOnSearch.isNotEmpty())
                assertTrue("$backend did not discover all five MCP tools", listedOnSearch.all { it == expected })
                catalogs.clear()
                val permission = ask("Run a read-only permission probe on these synthetic fixtures. Invoke read_segments exactly once " +
                    "with recordingId ${b.id} and limit 1. This candidate ID is intentionally supplied for a negative permissions test. " +
                    "Send that ID exactly so Android can verify and deny access. Report the tool's actual error if denied. " +
                    "Do not substitute the current recording ID or try another way to access it.")
                val toolSteps = permission.trace.filter { it.toolName != null }
                val denial = toolSteps.firstOrNull { it.toolName == "read_segments" &&
                    it.arguments?.let { args -> Json.parseToJsonElement(args).jsonObject["recordingId"] } == JsonPrimitive(b.id) }
                assertNotNull("$backend did not send the explicit B ID: $toolSteps", denial)
                assertEquals("failed", denial?.status)
                val error = Json.parseToJsonElement(checkNotNull(denial?.result)).jsonObject
                assertEquals(JsonPrimitive("recording_scope_denied"), error["code"])
                assertFalse("B call returned evidence", "evidence" in error)
                assertTrue("$backend did not discover all five MCP tools for the negative probe",
                    catalogs.isNotEmpty() && catalogs.all { it == expected })
                catalogs.clear()
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\nLive $backend stdio MCP: ${expected.sorted().joinToString()}, verified A citation, explicit B ID called, Android scope denied\n")
                })
                if (index == 0) compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
            }
        } finally {
            RecordingMcpBridge.testCatalogObserver = null
            previousKeys.forEach { (name, oldKey) -> app.settings.secrets.put(name, oldKey) }
            app.settings.update { it.copy(assistantBackend = previous.assistantBackend, pi = previous.pi, codex = previous.codex) }
        }
    }

    private fun openMcpRecording(recording: Recording) {
        compose.onNodeWithText(checkNotNull(recording.title)).activate()
        compose.onNodeWithText(activity.getString(R.string.ai_qa)).activate()
        compose.onNodeWithText(activity.getString(R.string.assistant_this_recording)).assertExists()
    }

    private fun addMcpRecordings(stamp: String, keyword: String): List<Recording> {
        val fixtures = listOf("A", "B").map { label ->
            Recording(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
                durationMs = 5_000, title = "MCP $label $stamp", stage = ProcessingStage.DONE,
                transcript = Transcript(listOf(Segment("mcp-$label", "$keyword synthetic recording $label.", 1_000, 2_000)),
                    "fixture", "test", "SEGMENT", 0))
        }
        runBlocking(Dispatchers.IO) {
            fixtures.forEach {
                PcmWav.writeMono(app.repository.audioFile(it.id), FloatArray(80_000), 16_000)
                app.repository.add(it)
            }
        }
        return fixtures
    }

    @Test fun workspacesSurviveTurnsAndRemainScoped() = runBlocking(Dispatchers.IO) {
        requirePhoneProbe()
        val launcher = sandbox
        val other = launcher.workspaceFor("other-${UUID.randomUUID()}")
        other.mkdirs()
        val outside = File(other, "canary.txt").apply { writeText("outside") }
        val global = launcher.workspaceFor(null)
        val name = "phone-fixture-${UUID.randomUUID()}"
        val durable = File(global, name)
        val script = """
            const fs = require('node:fs'), path = require('node:path');
            const [name, outside] = process.argv.slice(1);
            const persistent = path.join(process.env.SOUND_AGENT_WORKSPACE, name);
            let status;
            try { fs.readFileSync(outside); status = 'opened'; }
            catch (e) { status = e.code; }
            const previous = fs.existsSync(persistent) ? fs.readFileSync(persistent, 'utf8') : '';
            fs.writeFileSync(persistent, 'phone');
            fs.writeFileSync('scratch.txt', 'temporary');
            console.log(JSON.stringify({ previous, status }));
        """.trimIndent()
        try {
            val workspaces = mutableSetOf<String>()
            repeat(2) { turn ->
                launcher.start(launcher.node, listOf("-e", script, name, outside.absolutePath), null).use { child ->
                    assertTrue(workspaces.add(child.work.absolutePath))
                    val result = withTimeout(8_000) { child.receive() }
                    assertEquals(if (turn == 0) "" else "phone", result.getValue("previous").jsonPrimitive.content)
                    assertTrue(result["status"]?.jsonPrimitive?.content in setOf("EACCES", "EPERM"))
                    assertEquals("temporary", File(child.work, "scratch.txt").readText())
                }
                assertTrue(workspaces.none { File(it).exists() })
                assertEquals("phone", durable.readText())
                assertEquals("outside", outside.readText())
            }
        } finally {
            durable.delete()
            other.deleteRecursively()
        }
    }

    @Test fun settingsSwitchPiCodexPiAndKeepTheHomeConversation() {
        requirePhoneProbe()
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val discarded = "discarded-before-compaction-${UUID.randomUUID()}"
        runBlocking {
            val previous = app.recordingIndex.latestSession()!!
            app.recordingIndex.writeAssistantBody(null, app.recordingIndex.corpusGeneration,
                Json.encodeToJsonElement(previous.copy(
                    turns = previous.turns + dev.soundsoftware.core.agent.AgentTurn(discarded, "old answer", createdAt = 1),
                    memory = "Retained summary marker", contextInitialized = true,
                    contextMessages = emptyList(), compactions = 1, firstKeptTurn = previous.turns.size + 1)).jsonObject, false)
            app.assistant.load()
        }
        val session = app.assistant.state.value.session
        val stamp = UUID.randomUUID().toString().take(8)
        val keyword = "Larkspur$stamp"
        val first = "Larkspur launch date $stamp"
        val followUp = "What date did it move to $stamp?"
        val third = "Recheck the same recording $stamp"
        val fixture = Recording(
            id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
            durationMs = 10_000, title = "Larkspur validation", stage = ProcessingStage.DONE,
            transcript = Transcript(listOf(Segment("fixture", "$keyword moved to Thursday.", 1_000, 2_000)),
                "fixture", "test", "SEGMENT", 0),
        )
        runBlocking(Dispatchers.IO) {
            PcmWav.writeMono(app.repository.audioFile(fixture.id), FloatArray(160_000), 16_000)
            app.repository.add(fixture)
        }
        val previous = app.settings.state.value
        val keys = listOf(AssistantModelSettings.PI_KEY, AssistantModelSettings.CODEX_KEY)
        val previousKeys = keys.associateWith(app.settings.secrets::get)
        try {
            MockWebServer().use { pi ->
                MockWebServer().use { codex ->
                    pi.dispatcher = ModelFixture(false, keyword)
                    codex.dispatcher = ModelFixture(true, keyword)
                    pi.start()
                    codex.start()
                    val piUrl = pi.url("/pi-fixture/v1").newBuilder().host("localhost").build().toString()
                    val codexUrl = codex.url("/codex-fixture/v1").newBuilder().host("localhost").build().toString()
                    fun ask(text: String, completed: Int) {
                        submit(text)
                        compose.waitUntil(30_000) {
                            val state = app.assistant.state.value
                            !state.busy && (state.error != null || state.session.turns.size == session.turns.size + completed)
                        }
                        val state = app.assistant.state.value
                        assertNull(state.error, state.error)
                        assertEquals(session.id, state.session.id)
                        assertEquals(text, state.session.turns.last().question)
                        assertEquals(fixture.id, state.session.turns.last().citations.single().recordingId)
                        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
                        compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
                    }

                    val piKey = "fixture-pi"
                    val codexKey = "fixture-codex"
                    configure("Pi", piUrl, "fixture-pi-model", piKey)
                    ask(first, 1)
                    val firstPi = assertRequest(pi, "/pi-fixture/v1/chat/completions", piKey, "fixture-pi-model")
                    assertTrue(firstPi.contains(first))
                    assertFalse(firstPi.contains(discarded))
                    assertTrue(firstPi.contains("Retained summary marker"))
                    assertRequest(pi, "/pi-fixture/v1/chat/completions", piKey, "fixture-pi-model")

                    configure("Codex", codexUrl, "gpt-5.4", codexKey)
                    ask(followUp, 2)
                    val firstCodex = assertRequest(codex, "/codex-fixture/v1/responses", codexKey, "gpt-5.4")
                    assertTrue(firstCodex.contains(first) && firstCodex.contains(followUp))
                    assertFalse(firstCodex.contains(discarded))
                    assertTrue(firstCodex.contains("Retained summary marker"))
                    assertRequest(codex, "/codex-fixture/v1/responses", codexKey, "gpt-5.4")
                    val codexSaved = runBlocking { app.recordingIndex.latestSession()!! }
                    assertNotNull("Codex must retain provider usage", codexSaved.lastUsage)
                    assertTrue(codexSaved.contextMessages.any { it.role == "tool" && it.content.contains(keyword) })

                    configure("Pi")
                    ask(third, 3)
                    val resumedPi = assertRequest(pi, "/pi-fixture/v1/chat/completions", piKey, "fixture-pi-model")
                    assertTrue(listOf(first, followUp, third).all { resumedPi.contains(it) })
                    assertFalse(resumedPi.contains(discarded))
                    assertTrue(resumedPi.contains("Retained summary marker"))
                    assertRequest(pi, "/pi-fixture/v1/chat/completions", piKey, "fixture-pi-model")

                    val persisted = runBlocking { app.recordingIndex.latestSession() }
                    assertEquals(session.id, persisted?.id)
                    assertEquals(listOf(first, followUp, third), persisted?.turns?.takeLast(3)?.map { it.question })

                    configure("Codex")
                    val cancelQuestion = "Cancel live fixture $stamp"
                    val durable = File(sandbox.workspaceFor(null), "cancel-$stamp.txt").apply { writeText("retained") }
                    try {
                        submit(cancelQuestion)
                        val cancelling = assertRequest(codex, "/codex-fixture/v1/responses", codexKey, "gpt-5.4")
                        assertTrue(cancelling.contains(cancelQuestion))
                        compose.waitUntil(5_000) { app.assistant.busy.value && app.workServiceActive }
                        assertEquals(cancelQuestion, runBlocking { app.recordingIndex.interruptedQuestion(null) })
                        // Navigating away must keep the foreground request alive.
                        compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
                        assertTrue(app.assistant.busy.value)
                        compose.onNode(hasClickAction() and hasContentDescription(activity.getString(R.string.recording_assistant))).activate()
                        assertTrue(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("agent-") })
                        compose.onNodeWithText(activity.getString(R.string.stop)).activate()
                        compose.waitUntil(10_000) { app.assistant.state.value.cancelled }
                        compose.waitUntil(5_000) { !app.assistant.busy.value }
                        assertNull(runBlocking { app.recordingIndex.interruptedQuestion(null) })
                        assertEquals(persisted?.turns, app.assistant.state.value.session.turns)
                        assertEquals("retained", durable.readText())
                        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
                    } finally {
                        durable.delete()
                    }
                }
            }
        } finally {
            previousKeys.forEach { (name, key) -> app.settings.secrets.put(name, key) }
            app.settings.update { it.copy(assistantBackend = previous.assistantBackend, pi = previous.pi, codex = previous.codex) }
        }
    }

    @Test fun codexWritesAndPiProcessesTheSameWorkspaceFile() {
        requirePhoneProbe()
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val session = app.assistant.state.value.session
        val filename = "handoff-${UUID.randomUUID()}.json"
        val file = File(sandbox.workspaceFor(null), filename)
        val other = sandbox.workspaceFor("handoff-other-${UUID.randomUUID()}").apply { mkdirs() }
        val protected = File(other, "private.txt").apply { writeText("fixture") }
        val path = JsonPrimitive(filename)
        val pathToOther = JsonPrimitive(protected.absolutePath)
        val writeScript = """
            const fs = require('node:fs'), path = require('node:path');
            const crypto = require('node:crypto');
            const net = require('node:net');
            const { execFileSync } = require('node:child_process');
            try {
                fs.readFileSync($pathToOther);
                throw new Error('cross-workspace read succeeded');
            } catch (error) {
                if (!['ERR_ACCESS_DENIED', 'EACCES', 'EPERM'].includes(error.code)) throw error;
            }
            try {
                execFileSync('/system/bin/sh', ['-c', 'true']);
                throw new Error('child process succeeded');
            } catch (error) {
                if (error.code !== 'ERR_ACCESS_DENIED') throw error;
            }
            const socket = net.connect({ host: '127.0.0.1', port: 9 });
            socket.on('connect', () => { throw new Error('network access succeeded'); });
            socket.on('error', error => {
                if (!['EACCES', 'EPERM'].includes(error.code)) throw error;
                const file = path.join(process.env.SOUND_AGENT_WORKSPACE, $path);
                const value = crypto.randomInt(100_000, 999_999);
                fs.writeFileSync(file, JSON.stringify({ value }), { flag: 'wx' });
                console.log('workspace file written');
            });
        """.trimIndent()
        val processScript = """
            const fs = require('node:fs'), path = require('node:path');
            const file = path.join(process.env.SOUND_AGENT_WORKSPACE, $path);
            const data = JSON.parse(fs.readFileSync(file, 'utf8'));
            data.doubled = data.value * 2;
            fs.writeFileSync(file, JSON.stringify(data));
            console.log(data.doubled);
        """.trimIndent()

        val previous = app.settings.state.value
        val keys = listOf(AssistantModelSettings.PI_KEY, AssistantModelSettings.CODEX_KEY)
        val previousKeys = keys.associateWith(app.settings.secrets::get)
        var completed = false
        try {
            MockWebServer().use { codex ->
                MockWebServer().use { pi ->
                    codex.dispatcher = ModelFixture(true, script = writeScript)
                    pi.dispatcher = ModelFixture(false, script = processScript)
                    codex.start()
                    pi.start()
                    fun awaitTurn(count: Int): dev.soundsoftware.core.agent.AgentTurn {
                        compose.waitUntil(30_000) {
                            val state = app.assistant.state.value
                            !state.busy && (state.error != null || state.session.turns.size >= session.turns.size + count)
                        }
                        val state = app.assistant.state.value
                        assertNull(state.error, state.error)
                        assertEquals(session.id, state.session.id)
                        val turn = state.session.turns.last()
                        val tool = turn.trace.single { it.toolName == "run_node" }
                        assertEquals(tool.result, "done", tool.status)
                        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
                        compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
                        return turn
                    }

                    configure("Codex", codex.url("/codex-node").toString(), "gpt-5.4", "fixture-node-codex")
                    submit("/node Write the script result into $filename in the persistent workspace.")
                    val first = awaitTurn(1)
                    assertEquals("workspace file written", first.answer)
                    val before = Json.parseToJsonElement(file.readText()).jsonObject
                    val value = before.getValue("value").jsonPrimitive.int
                    assertFalse(first.answer.contains(value.toString()))
                    assertFalse("doubled" in before)

                    configure("Pi", pi.url("/pi-node").toString(), "fixture-pi-model", "fixture-node-pi")
                    submit("/node Read $filename, double the saved value and write back into the same file.")
                    val second = awaitTurn(2)
                    val after = Json.parseToJsonElement(file.readText()).jsonObject
                    assertEquals(value, after.getValue("value").jsonPrimitive.int)
                    assertEquals(value * 2, after.getValue("doubled").jsonPrimitive.int)
                    assertEquals((value * 2).toString(), second.answer)
                    val piRequest = assertRequest(pi, "/pi-node/chat/completions", "fixture-node-pi", "fixture-pi-model")
                    assertFalse(piRequest.contains(value.toString()))
                    assertRequest(pi, "/pi-node/chat/completions", "fixture-node-pi", "fixture-pi-model")
                    assertRequest(codex, "/codex-node/responses", "fixture-node-codex", "gpt-5.4")
                    assertRequest(codex, "/codex-node/responses", "fixture-node-codex", "gpt-5.4")
                    assertEquals("fixture", protected.readText())
                    android.util.Log.i("NodeWorkspacePhone", "$filename value=$value doubled=${value * 2}")
                    completed = true
                }
            }
        } finally {
            if (!completed) file.delete()
            other.deleteRecursively()
            previousKeys.forEach { (name, key) -> app.settings.secrets.put(name, key) }
            app.settings.update { it.copy(assistantBackend = previous.assistantBackend, pi = previous.pi, codex = previous.codex) }
        }
    }

    @Test fun realModelsGenerateAndShareAWorkspaceFile() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveModel") == "true")
        requirePhoneProbe()
        check(app.instrumentationDataDir != null && app.repository.recordings.value.isEmpty()) {
            "Live model verification requires a fresh isolated recording library."
        }
        val base = requireNotNull(args.getString("liveBase")) { "Provide a real model gateway." }
        val model = requireNotNull(args.getString("liveModelName"))
        val keyFile = File(app.cacheDir, "agent-live-key")
        val key = keyFile.takeIf { it.isFile }?.let { it.readText().trim().also { _ -> it.delete() } } ?: args.getString("liveKey").orEmpty()
        val previous = app.settings.state.value
        val keys = listOf(AssistantModelSettings.PI_KEY, AssistantModelSettings.CODEX_KEY)
        val previousKeys = keys.associateWith(app.settings.secrets::get)
        val filename = "live-handoff-${UUID.randomUUID()}.json"
        val file = File(sandbox.workspaceFor(null), filename)
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val session = app.assistant.state.value.session
        assertTrue(session.turns.isEmpty())

        fun awaitTurn(number: Int): dev.soundsoftware.core.agent.AgentTurn {
            compose.waitUntil(90_000) {
                val state = app.assistant.state.value
                !state.busy && (state.error != null || state.session.turns.size >= number)
            }
            val state = app.assistant.state.value
            assertNull(state.error, state.error)
            assertEquals(session.id, state.session.id)
            val turn = state.session.turns.last()
            val tools = turn.trace.filter { it.toolName == "run_node" }
            assertTrue("The real model did not complete a run_node call: ${tools.map { it.result }}",
                tools.any { it.status == "done" })
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
            compose.onNodeWithContentDescription(activity.getString(R.string.back)).activate()
            return turn
        }

        var completed = false
        try {
            configure("Codex", base, model, key)
            submit("/node Generate and run JavaScript. Use node:crypto to generate a random integer between 100000 and " +
                "999999 at script runtime. Write a JSON object with the single field value to $filename in the " +
                "persistent workspace. Do not print or mention the number. On success print the filename and report it.")
            val codex = awaitTurn(1)
            assertTrue(codex.answer.contains(filename))
            val first = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(setOf("value"), first.keys)
            val value = first.getValue("value").jsonPrimitive.int
            assertTrue(value in 100_000..999_999)
            assertFalse(codex.answer.contains(value.toString()))
            assertTrue(codex.trace.none { listOf(it.result, it.arguments).any { text -> text?.contains(value.toString()) == true } })

            configure("Pi", base, model, key)
            submit("/node Generate and run JavaScript to read the existing $filename from the persistent workspace. " +
                "Keep its value field and add a doubled field equal to twice that value. Update the same JSON file " +
                "and print the computed doubled value. Report the result you actually read and calculated.")
            val pi = awaitTurn(2)
            val second = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(value, second.getValue("value").jsonPrimitive.int)
            assertEquals(value * 2, second.getValue("doubled").jsonPrimitive.int)
            assertTrue(pi.answer.replace(",", "").contains((value * 2).toString()))
            assertEquals(session.id, runBlocking { app.recordingIndex.latestSession()?.id })
            completed = true
            val report = android.os.Bundle().apply {
                putString("stream", "\nLive Node handoff: $filename, value=$value, doubled=${value * 2}\n")
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
        } finally {
            if (!completed) file.delete()
            previousKeys.forEach { (name, oldKey) -> app.settings.secrets.put(name, oldKey) }
            app.settings.update { it.copy(assistantBackend = previous.assistantBackend, pi = previous.pi, codex = previous.codex) }
        }
    }

    private fun configure(name: String, base: String? = null, model: String = "", key: String = "") {
        compose.onNodeWithContentDescription(activity.getString(R.string.settings)).activate()
        compose.onNodeWithText(activity.getString(R.string.assistant_backend)).performScrollTo().activate()
        compose.onNodeWithContentDescription("Pi").assertExists()
        compose.onNodeWithContentDescription("Codex").assertExists()
        for (label in listOf("Automatic", "Built-in assistant", "自动", "内置助手")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText(name).activate()
        if (base != null) {
            val fields = compose.onAllNodes(hasSetTextAction())
            listOf(base, model, key).forEachIndexed { index, value ->
                fields[index].performScrollTo().performTextReplacement(value)
            }
        }
        compose.onNodeWithText(activity.getString(R.string.save)).activate()
        val selected = if (name == "Pi") AssistantBackend.PI else AssistantBackend.CODEX
        compose.waitUntil(5_000) { app.settings.state.value.assistantBackend == selected }
        compose.onNodeWithContentDescription(activity.getString(R.string.recordings)).activate()
    }

    private fun submit(text: String) {
        val label = activity.getString(R.string.recording_assistant)
        val ai = hasClickAction() and hasContentDescription(label)
        if (compose.onAllNodes(ai).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(ai)
        }
        compose.onNode(ai).activate()
        submitInAssistant(text)
    }

    private fun submitInAssistant(text: String) {
        val placeholder = activity.getString(R.string.ask_recordings_placeholder)
        compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true)).performTextInput(text)
        compose.onNodeWithContentDescription(activity.getString(R.string.send)).activate()
    }

    private fun assertRequest(server: MockWebServer, path: String, key: String, model: String): String {
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals(path, request.path)
        assertTrue("Unexpected fixture Authorization header", request.getHeader("Authorization") == "Bearer $key")
        val body = request.body.readUtf8()
        assertEquals(model, Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content)
        return body
    }

    internal class ScopedMcpFixture(private val a: Recording, private val b: Recording, val keyword: String) {
        data class Reply(val name: String = "", val namespace: String? = null,
            val arguments: JsonObject = buildJsonObject {}, val answer: String? = null)

        @Volatile var failure: Throwable? = null
        @Volatile var discovered = emptySet<String>()
        @Volatile var denied = false
        private var evidence: JsonObject? = null
        private val expected = setOf("list_recordings", "search_recordings", "read_segments", "list_todos", "list_highlights")

        fun plan(id: Int, body: JsonObject, output: JsonElement?): Reply {
            val definitions = definitions(body).toMap()
            assertEquals("Tool definitions at call $id", expected, definitions.keys)
            discovered = definitions.keys
            if (id == 1) return definitions.getValue("search_recordings").copy(arguments = buildJsonObject { put("query", keyword) })
            val result = objects(output).firstOrNull { "evidence" in it || "code" in it || "error" in it }
            checkNotNull(result) { "Missing tool result at call $id: $output" }
            if (id == 2) {
                evidence = result.getValue("evidence").jsonArray.single().jsonObject
                assertEquals(a.id, evidence!!.getValue("recordingId").jsonPrimitive.content)
                assertTrue(evidence!!.getValue("quote").jsonPrimitive.content.contains("recording A"))
                return definitions.getValue("read_segments").copy(arguments = buildJsonObject { put("recordingId", b.id) })
            }
            assertEquals(3, id)
            assertEquals("recording_scope_denied", result.getValue("code").jsonPrimitive.content)
            assertFalse("A denied call returned evidence", "evidence" in result)
            denied = true
            val citation = checkNotNull(evidence).filterKeys { it in setOf("recordingId", "segmentId", "revision", "quote") }
            val answer = buildJsonObject {
                put("found", true); put("answer", "Verified recording A. Android rejected recording B.")
                put("citations", JsonArray(listOf(JsonObject(citation))))
            }
            return Reply(answer = answer.toString())
        }

        private fun definitions(value: JsonElement, namespace: String? = null): Sequence<Pair<String, Reply>> = when (value) {
            is JsonObject -> sequence {
                val name = (value["name"] as? JsonPrimitive)?.contentOrNull
                val suppliedNamespace = (value["namespace"] as? JsonPrimitive)?.contentOrNull
                val toolNamespace = if ((value["type"] as? JsonPrimitive)?.content == "namespace") name else
                    suppliedNamespace ?: namespace
                if (name != null && ("parameters" in value || "inputSchema" in value || "description" in value)) {
                    expected.find { name == it || name.endsWith("__$it") }?.let { yield(it to Reply(name, toolNamespace)) }
                }
                for (child in value.values) yieldAll(definitions(child, toolNamespace))
            }
            is JsonArray -> value.asSequence().flatMap { definitions(it, namespace) }
            else -> emptySequence()
        }

        private fun objects(value: JsonElement?): Sequence<JsonObject> = when (value) {
            is JsonObject -> sequenceOf(value) + value.values.asSequence().flatMap(::objects)
            is JsonArray -> value.asSequence().flatMap(::objects)
            is JsonPrimitive -> {
                val text = value.content.substringAfter("\nOutput:\n").trimStart()
                if (value.isString && text.startsWith('{')) {
                    objects(runCatching { Json.parseToJsonElement(text) }.getOrNull())
                } else emptySequence()
            }
            else -> emptySequence()
        }
    }

    internal class ModelFixture(private val responses: Boolean, private val keyword: String = "", private val script: String? = null,
        private val scoped: ScopedMcpFixture? = null) : Dispatcher() {
        private fun objects(value: JsonElement?): Sequence<JsonObject> = when (value) {
            is JsonObject -> sequenceOf(value) + value.values.asSequence().flatMap(::objects)
            is JsonArray -> value.asSequence().flatMap(::objects)
            is JsonPrimitive -> {
                val text = value.content.substringAfter("\nOutput:\n").trimStart()
                if (value.isString && (text.startsWith('{') || text.startsWith('[')))
                    objects(runCatching { Json.parseToJsonElement(text) }.getOrNull()) else emptySequence()
            }
            else -> emptySequence()
        }
        private fun toolDefinition(value: JsonElement, expected: String, namespace: String? = null): Pair<String, String?>? {
            if (value is JsonObject) {
                val name = (value["name"] as? JsonPrimitive)?.content
                val ns = if ((value["type"] as? JsonPrimitive)?.content == "namespace") name else namespace
                if (("parameters" in value || "inputSchema" in value) &&
                    (name == expected || name?.endsWith("__$expected") == true)) return name to namespace
                value.values.forEach { child -> toolDefinition(child, expected, ns)?.let { return it } }
            } else if (value is JsonArray) value.forEach { child -> toolDefinition(child, expected, namespace)?.let { return it } }
            return null
        }
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val requestBody = request.body.clone().readUtf8()
            if ("Cancel live fixture" in requestBody) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            val body = Json.parseToJsonElement(requestBody).jsonObject
            val id = calls.incrementAndGet()
            fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
            val messages = body[if (responses) "input" else "messages"]?.jsonArray.orEmpty()
            val output = if (responses) messages.map { it.jsonObject }.lastOrNull { it.text("type") == "function_call_output" }
            else messages.lastOrNull()?.jsonObject?.takeIf { it.text("role") == "tool" }
            val rawOutput = output?.get(if (responses) "output" else "content")
            val plan = try { scoped?.plan(id, body, rawOutput) } catch (error: Throwable) {
                scoped?.failure = error
                return MockResponse().setResponseCode(400)
            }
            val compacting = requestBody.contains("总结较早的录音助手上下文")
            val answer = if (compacting) """{"summary":"retained-summary after compaction"}""" else plan?.answer ?: if (plan != null) null else objects(rawOutput).firstOrNull { "evidence" in it || "stdout" in it || "error" in it }?.toString()?.let {
                val resultBody = Json.parseToJsonElement(it).jsonObject
                val evidence = resultBody["evidence"]?.jsonArray.orEmpty()
                buildJsonObject {
                    put("found", true)
                    put("answer", if (script == null) "Larkspur moved to Thursday."
                        else resultBody.text("stdout")?.trim().takeUnless { it.isNullOrBlank() }
                            ?: resultBody.text("error") ?: "No output")
                    put("citations", JsonArray(evidence.map { entry ->
                        JsonObject(entry.jsonObject.filterKeys { key ->
                            key in setOf("recordingId", "segmentId", "revision", "quote")
                        })
                    }))
                }.toString()
            }
            val declared = toolDefinition(body, if (script == null) "search_recordings" else "run_node")
            val toolName = plan?.name ?: declared?.first ?: if (script == null) "search_recordings" else "run_node"
            val args = (plan?.arguments ?: buildJsonObject {
                if (script == null) put("query", keyword) else put("script", script)
            }).toString()
            val callId = "fixture-call-$id"
            return if (!responses) MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        put("finish_reason", if (answer == null) "tool_calls" else "stop")
                        putJsonObject("message") {
                            if (answer != null) put("content", answer)
                            else putJsonArray("tool_calls") {
                                addJsonObject {
                                    put("id", callId); put("type", "function")
                                    putJsonObject("function") { put("name", toolName); put("arguments", args) }
                                }
                            }
                        }
                    }
                }
            }.toString()) else {
                val item = buildJsonObject {
                    put("status", "completed")
                    put("id", if (answer == null) "fc_$id" else "msg_$id")
                    if (answer == null) {
                        put("type", "function_call"); put("call_id", callId)
                        put("name", toolName); put("arguments", args)
                        (plan?.namespace ?: declared?.second)?.let { put("namespace", it) }
                    } else {
                        put("type", "message"); put("role", "assistant")
                        putJsonArray("content") {
                            addJsonObject { put("type", "output_text"); put("text", answer); put("annotations", JsonArray(emptyList())) }
                        }
                    }
                }
                val response = buildJsonObject {
                    put("id", "resp_$id"); put("object", "response"); put("model", "gpt-5.4")
                    put("status", "completed"); put("created_at", 0); put("parallel_tool_calls", false)
                    put("output", JsonArray(listOf(item)))
                    putJsonObject("usage") {
                        put("input_tokens", 20); put("output_tokens", 8); put("total_tokens", 28)
                        putJsonObject("input_tokens_details") { put("cached_tokens", 0) }
                        putJsonObject("output_tokens_details") { put("reasoning_tokens", 0) }
                    }
                }
                val frames = listOf(
                    "response.created" to ("response" to JsonObject(response + mapOf("status" to JsonPrimitive("in_progress"), "output" to JsonArray(emptyList())))),
                    "response.output_item.added" to ("item" to item),
                    "response.output_item.done" to ("item" to item),
                    "response.completed" to ("response" to response),
                )
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frames.joinToString("") { (type, data) ->
                    val event = buildJsonObject { put("type", type); put(data.first, data.second); put("output_index", 0) }
                    "event: $type\ndata: $event\n\n"
                })
            }
        }
    }
}
