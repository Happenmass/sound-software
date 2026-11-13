package dev.soundsoftware.core

import dev.soundsoftware.core.model.*
import dev.soundsoftware.core.transcript.*
import org.junit.Assert.*
import org.junit.Test

class SpeakerReanalysisTest {
    private fun base()=Recording("r",0,6000,title="用户标题",titleEditedByUser=true,
        transcript=Transcript(listOf(Segment("a","手动修改过的文字",0,3000,"old-a",true),Segment("b","第二段",3000,6000,"old-b")),"asr-model","cpu","APPROXIMATE_SEGMENT",10,listOf("ASR时间近似")),
        speakers=listOf(Speaker("old-a",1,"小陈"),Speaker("old-b",2,"小王")),
        summary=MeetingSummary(abstract="旧纪要",todos=listOf(TodoItem("t","完成测试",done=true))),qa=listOf(QaTurn("旧问题","旧回答",0)))
    @Test fun updatesOnlyIdentityAndRetainsWordsEditsNotesAndManualTitle() {
        val base=base();val out=SpeakerReanalysis.commit(base,base,listOf(SpeakerTurn(0,3000,8),SpeakerTurn(3000,6000,2)),listOf("说话人测试警告"),null,100)
        assertEquals(base.transcript!!.segments.map{it.copy(speakerId=null)},out.transcript!!.segments.map{it.copy(speakerId=null)})
        assertEquals(base.title,out.title);assertEquals(base.summary,out.summary);assertEquals(base.qa,out.qa)
        assertEquals(base.transcript.model,out.transcript.model);assertEquals(base.transcript.createdAt,out.transcript.createdAt)
        assertEquals(listOf("小陈","小王"),out.speakers.map{it.name});assertEquals(2,out.speakerAnalysis!!.resultCount)
    }
    @Test fun rawClusterIdDoesNotAccidentallyReattachAnOldName() {
        val base=base();val out=SpeakerReanalysis.commit(base,base,listOf(SpeakerTurn(0,6000,0)),emptyList(),1,101)
        assertNull(out.speakers.single().name);assertTrue(out.speakers.single().id.startsWith("speaker-101-"));assertEquals(1,out.options!!.speakerCount)
    }
    @Test fun refusesConcurrentTranscriptOrSpeakerEdits() {
        val base=base();val turns=listOf(SpeakerTurn(0,6000,0))
        assertTrue(runCatching {SpeakerReanalysis.commit(base,base.copy(transcript=base.transcript!!.copy(segments=emptyList())),turns,emptyList(),null,100)}.isFailure)
        assertTrue(runCatching {SpeakerReanalysis.commit(base,base.copy(speakers=listOf(Speaker("x",1,"新命名"))),turns,emptyList(),null,100)}.isFailure)
    }
    @Test fun preservesConcurrentUnrelatedChangesAndUnknownSpeech() {
        val base=base();val current=base.copy(title="新标题",summary=base.summary!!.copy(abstract="新纪要"))
        val out=SpeakerReanalysis.commit(base,current,listOf(SpeakerTurn(0,3000,-1),SpeakerTurn(3000,6000,1)),emptyList(),null,100)
        assertEquals(current.title,out.title);assertEquals(current.summary,out.summary);assertNull(out.transcript!!.segments.first().speakerId)
        assertEquals(2,out.transcript.segments.size)
    }    @Test fun legacySummaryIsPreservedButExportedWithReviewNotice() {
        val base=base();val out=SpeakerReanalysis.commit(base,base,listOf(SpeakerTurn(0,6000,0)),emptyList(),null,100)
        val blocks=dev.soundsoftware.core.export.RecordingExport.blocks(out,"title","",dev.soundsoftware.core.export.ExportOptions())
        assertTrue(blocks.toString().contains("尚未重新生成"));assertEquals(base.summary,out.summary)
    }

}
