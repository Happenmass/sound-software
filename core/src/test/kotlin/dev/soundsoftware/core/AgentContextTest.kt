package dev.soundsoftware.core

import dev.soundsoftware.core.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

class AgentContextTest {
    @Test fun codexReceivesOnlyRetainedModelContextAfterPiCompaction() {
        val session = AgentSession(
            turns = listOf(AgentTurn("discarded-question", "old answer")),
            memory = "Relevant summary",
            compactions = 1,
            contextInitialized = true,
            contextMessages = listOf(AgentMessage("user", "retained-question")),
        )
        val input = session.codexInput("next question")
        assertFalse("Compacted history must not be expanded", input.contains("discarded-question"))
        assertTrue(input.contains("Relevant summary"))
        assertTrue(input.contains("retained-question"))
        assertTrue(input.endsWith("next question"))
    }

    @Test fun initializedEmptyContextDoesNotResurrectVisibleHistory() {
        val session = AgentSession(turns = listOf(AgentTurn("deleted source", "old answer")))
        assertEquals("next", session.initializeContext(invalidated = true).codexInput("next"))
        assertEquals(2, session.initializeContext().contextMessages.size)
        assertEquals(session.turns, session.initializeContext(true).turns)
    }

    @Test fun compactionRetainsAllToolParentsAndClearsOnlyOldUsage() = runTest {
        val messages = (0..9).map { AgentMessage("user", "old-$it") } + listOf(
            AgentMessage("assistant", calls = listOf(WireToolCall("a", "read_segments", "{}"))),
            AgentMessage("assistant", calls = listOf(WireToolCall("b", "read_segments", "{}"))),
            AgentMessage("tool", "page a", "a"), AgentMessage("user", "continue"),
            AgentMessage("tool", "page b", "b"), AgentMessage("assistant", "answer"))
        val before = AgentSession(contextInitialized = true, contextMessages = messages, lastUsage = AgentUsage(199900, 100))
        val after = before.compactContext("next") { input ->
            assertTrue(input.last().content.contains("old-9"))
            assertFalse(input.last().content.contains("page a"))
            AgentReply("""{"summary":"source and pagination"}""", usage = AgentUsage(200000, 1000))
        }
        assertEquals(messages.drop(10), after.contextMessages)
        assertEquals(1, after.compactions); assertNull(after.lastUsage)
        val completed = after.completeTurn(AgentTurn("next", "answer"), emptyList(), AgentUsage(12, 3))
        assertEquals(15L, completed.lastUsage!!.totalTokens)
    }

    @Test fun missingUsageDoesNotUseCharacterCountAndCancellationDoesNotCommit() = runTest {
        val before = AgentSession(contextInitialized = true, contextMessages = (0..9).map { AgentMessage("user", "x".repeat(30000)) })
        assertEquals(before, before.compactContext("next") { error("No provider usage") })
        val entered = CompletableDeferred<Unit>()
        val job = launch { before.copy(lastUsage = AgentUsage(200000, 0)).compactContext("next") { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin()
        assertEquals("", before.memory); assertEquals(10, before.contextMessages.size)
        assertTrue(runCatching { before.copy(lastUsage = AgentUsage(200000, 0)).compactContext("next") { AgentReply("{}") } }.isFailure)
    }

    @Test fun actualPiToCodexToPiHandoffKeepsCompactedContextToolsAndVisibleHistory() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/pi-agent/handoff-fixture.mjs").exists() }
        assumeTrue("Run npm ci in tools/pi-agent for the cross-runtime contract test",
            File(root, "tools/pi-agent/node_modules/@earendil-works/pi-agent-core").exists())
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun pi(session: AgentSession, question: String): Pair<AgentSession, String> {
            val process = ProcessBuilder("node", "tools/pi-agent/handoff-fixture.mjs").directory(root).start()
            process.outputStream.bufferedWriter().use { it.write(buildJsonObject {
                put("session", json.encodeToJsonElement(session)); put("question", question)
                put("systemPrompt", AgentPrompts.SYSTEM); put("compactionPrompt", AgentPrompts.COMPACT)
            }.toString()) }
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
            val result = json.parseToJsonElement(output).jsonObject
            return json.decodeFromJsonElement<AgentSession>(result.getValue("session")) to result.getValue("seen").toString()
        }
        val start = AgentSession(turns = (0..5).map { AgentTurn("discarded-$it", "old-$it") }, lastUsage = AgentUsage(200000, 0))
        val (fromPi, _) = pi(start, "Pi question")
        assertEquals(1, fromPi.compactions)
        assertFalse(fromPi.codexInput("Codex question").contains("discarded-0"))
        val codex = fromPi.completeTurn(AgentTurn("Codex question", "Codex verified answer"), listOf(
            AgentMessage("assistant", calls = listOf(WireToolCall("call", "read_segments", "{}"))),
            AgentMessage("tool", "pagination-marker", "call")), AgentUsage(10, 2))
        val (back, request) = pi(codex, "Back to Pi")
        assertTrue(request.contains("summary from Pi")); assertTrue(request.contains("pagination-marker"))
        assertTrue(request.contains("Codex verified answer")); assertFalse(request.contains("discarded-0"))
        assertEquals(start.turns, back.turns.take(6)); assertEquals(9, back.turns.size)
        assertEquals(9, back.firstKeptTurn)
    }
}
