package dev.soundsoftware.core

import dev.soundsoftware.core.agent.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordingAnswerTest {
    private val source = RecordingCitation("r", "s", "Friday release", "revision")
    private val tools = object : RecordingTools {
        override suspend fun execute(name: String, arguments: JsonObject) = error("unused")
        override suspend fun resolve(ref: RecordingCitation) = ref.takeIf {
            it.recordingId == source.recordingId && it.revision == source.revision && source.quote.contains(it.quote)
        }?.copy(title = "Trusted title", startMs = 10)
    }
    private fun answer(refs: List<RecordingCitation>, found: Boolean = true, text: String = "Friday") = buildJsonObject {
        put("found", found); put("answer", text); put("citations", Json.encodeToJsonElement(refs))
    }.toString()

    @Test fun historyCannotSubstituteForCurrentRunEvidenceAndDeletedSourcesFail() = runTest {
        val unverified = verifyRecordingAnswer(answer(listOf(source)), emptySet(), tools, "en")
        assertTrue(unverified.citations.isEmpty()); assertNotNull(unverified.evidenceWarning)
        val deleted = object : RecordingTools by tools { override suspend fun resolve(ref: RecordingCitation): RecordingCitation? = null }
        assertTrue(verifyRecordingAnswer(answer(listOf(source)), setOf(source), deleted, "en").citations.isEmpty())
    }
    @Test fun rejectsFabricatedVersionAndQuoteButKeepsValidAnswerAndServerMetadata() = runTest {
        val checked = verifyRecordingAnswer(answer(listOf(source.copy(quote = "Friday", title = "Forged"),
            source.copy(revision = "wrong"), source.copy(quote = "Monday"))), setOf(source), tools, "en")
        assertEquals("Friday", checked.answer); assertEquals(1, checked.citations.size)
        assertEquals("Trusted title", checked.citations.single().title); assertNotNull(checked.evidenceWarning)
    }
    @Test fun unknownIsDeterministicAndMalformedOutputFails() = runTest {
        assertFalse(verifyRecordingAnswer(answer(emptyList(), false, "Invented"), emptySet(), tools, "en").answer.contains("Invented"))
        assertTrue(runCatching { verifyRecordingAnswer("{}", emptySet(), tools, "en") }.isFailure)
        assertTrue(runCatching { verifyRecordingAnswer(answer(emptyList(), text = ""), emptySet(), tools, "en") }.isFailure)
    }
    @Test fun longAnswersAndManyCitationsAreNotSilentlyTruncated() = runTest {
        val refs = (0..59).map { source.copy(segmentId = "s$it") }
        val text = "Complete answer. ".repeat(1000)
        val checked = verifyRecordingAnswer(answer(refs, text = text), refs.toSet(), tools, "en")
        assertEquals(text.trim(), checked.answer); assertEquals(60, checked.citations.size)
    }
}
