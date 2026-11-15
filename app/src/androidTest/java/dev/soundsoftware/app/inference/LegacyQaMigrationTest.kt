package dev.soundsoftware.app.inference

import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.agent.RecordingIndex
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegacyQaMigrationTest {
    @Test fun oldQuestionsMigrateOnceAndDoNotReturnAfterClearingHistory() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val base = Recording("old", 10, 1000, title = "Old recording", transcript = Transcript(
                listOf(Segment("s", "Friday release", 0, 1000)), "fixture", "fixture", "APPROXIMATE_SEGMENT", 10))
            val record = base.copy(legacyQa = listOf(LegacyQaTurn("When?", "Friday", 123,
                listOf(EvidenceRef("s", "Friday release")), MeetingAnalyzer.transcriptHash(base))))
            val database = File(dir, "recordings.db")
            RecordingIndex(database).use { index ->
                index.upsert(record)
                val session = index.latestSession(record.id)
                assertNotNull("Legacy questions must appear in the recording's assistant", session)
                assertEquals("When?", session!!.turns.single().question)
                assertEquals(123L, session.turns.single().createdAt)
                assertNotNull(index.resolve(session.turns.single().citations.single()))
                index.upsert(record)
                assertEquals(session, index.latestSession(record.id))
                index.clearSessions(record.id)
            }
            RecordingIndex(database).use { reopened ->
                reopened.reconcile(listOf(record))
                assertNull("Clearing history must not resurrect legacy questions", reopened.latestSession(record.id))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun mergingLegacyHistoryPreservesCompactedContextAndSurvivesTranscriptReplacement() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            RecordingIndex(File(dir, "recordings.db")).use { index ->
                val repo = RecordingRepository(dir, index)
                val base = Recording("old", 10, 1000, transcript = Transcript(listOf(Segment("s", "Friday", 0, 1000)), "test", "test", "SEGMENT", 10))
                PcmWav.writeMono(repo.audioFile(base.id), FloatArray(1600), 16000)
                repo.add(base)
                val existing = AgentSession(turns = listOf(AgentTurn("new", "new answer", createdAt = 200)),
                    memory = "compressed", contextInitialized = true, contextMessages = listOf(AgentMessage("user", "retained")), compactions = 2)
                index.saveSession(existing, base.id)
                val old = LegacyQaTurn("old question", "Friday", 100, listOf(EvidenceRef("s", "Friday")), MeetingAnalyzer.transcriptHash(base))
                repo.update(base.id) { it.copy(legacyQa = listOf(old)) }
                val merged = index.latestSession(base.id)!!
                assertEquals(existing.id, merged.id)
                assertEquals(listOf("old question", "new"), merged.turns.map { it.question })
                assertEquals(existing.contextMessages, merged.contextMessages); assertEquals("compressed", merged.memory)
                repo.update(base.id) { it.copy(transcript = base.transcript!!.copy(segments = listOf(Segment("s", "Monday", 0, 1000)))) }
                assertEquals(listOf(old), repo.get(base.id)!!.legacyQa)
                assertNull(index.resolve(merged.turns.first().citations.single()))
                repo.load()
                assertEquals(merged, index.latestSession(base.id))
                assertEquals(listOf(old), repo.get(base.id)!!.legacyQa)
            }
        } finally { dir.deleteRecursively() }
    }
}
