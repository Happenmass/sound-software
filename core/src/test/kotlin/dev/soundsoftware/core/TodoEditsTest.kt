package dev.soundsoftware.core

import dev.soundsoftware.core.analysis.AnalysisException
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.analysis.SummaryCommit
import dev.soundsoftware.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TodoEditsTest {
    private val source=Recording("r",0,1000,transcript=Transcript(listOf(Segment("s","测试任务原文",0,1000)),"fixture","test","SEGMENT",0),
        summary=MeetingSummary(abstract="保留摘要",todos=listOf(TodoItem("a","已完成任务",done=true),TodoItem("b","另一任务")),chapters=listOf(Chapter(0,"保留章节"))))

    @Test fun deletingCompletedTaskPreservesOtherNotesAndOriginalEvidence() {
        val edited=source.withoutTodo("a")
        assertEquals(listOf("b"),edited.summary!!.todos.map {it.id})
        assertEquals(source.summary!!.copy(todos=edited.summary!!.todos),edited.summary)
        assertEquals(source.copy(summary=edited.summary),edited)
        assertEquals(source,source.withoutTodo("missing"))
        assertEquals(source.copy(summary=null),source.copy(summary=null).withoutTodo("a"))
    }

    @Test fun summaryStartedBeforeDeletionCannotRestoreRemovedTask() {
        val generated=source.summary!!.copy(sourceTranscriptHash=MeetingAnalyzer.transcriptHash(source))
        val current=source.withoutTodo("a")
        assertTrue(runCatching {SummaryCommit.reconcile(source,current,generated)}.exceptionOrNull() is AnalysisException)
        assertEquals(listOf("b"),current.summary!!.todos.map {it.id})
    }
}
