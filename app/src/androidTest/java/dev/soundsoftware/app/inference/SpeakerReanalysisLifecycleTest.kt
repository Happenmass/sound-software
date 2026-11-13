package dev.soundsoftware.app.inference

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.*
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import dev.soundsoftware.core.transcript.SpeakerTurn
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerReanalysisLifecycleTest {
    private suspend fun fixture(run:suspend(Processor,RecordingRepository,FakeAnalyzer)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext;val prefix="speakers-${UUID.randomUUID()}-"
        val context=object:ContextWrapper(base){override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)}
        val dir=File(base.cacheDir,prefix).apply{mkdirs()};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val repo=RecordingRepository(dir);PcmWav.writeMono(repo.audioFile("r"),FloatArray(16000*4){.1f})
            repo.add(Recording("r",0,4000,title="原始标题",stage=ProcessingStage.DONE,transcript=Transcript(listOf(Segment("s","用户编辑文字",0,4000,edited=true)),"keep-asr","cpu","APPROXIMATE_SEGMENT",0),summary=MeetingSummary(abstract="保留纪要")))
            val fake=FakeAnalyzer();val processor=Processor(repo,SettingsStore(context),File(dir,"no-asr-models"),File(dir,"checkpoints"),scope,diarizer=fake){}
            run(processor,repo,fake)
        } finally {scope.coroutineContext.job.cancelAndJoin();base.deleteSharedPreferences(prefix+"app-settings");dir.deleteRecursively()}
    }
    class FakeAnalyzer:SpeakerAnalyzer {
        override val isReady=true;var block=false;var empty=false;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        override suspend fun analyze(wav:PcmWav,speakerCount:Int?,onProgress:(Float)->Unit):DiarizationAnalysis {
            entered.complete(Unit);if(block)release.await()
            return DiarizationAnalysis(if(empty)emptyList() else listOf(SpeakerTurn(0,4000,0)))
        }
    }
    @Test fun recomputesWithoutAsrModelsAndPersistsOriginalText()=runBlocking {fixture {processor,repo,_->
        val old=repo.get("r")!!;processor.rediarize("r",null).join();val saved=repo.get("r")!!
        assertEquals(old.transcript!!.segments.map{it.text},saved.transcript!!.segments.map{it.text});assertEquals(old.summary,saved.summary)
        assertEquals(old.transcript!!.model,saved.transcript!!.model);assertEquals(1,saved.speakers.size);assertNotNull(saved.speakerAnalysis);assertFalse(processor.busy.value)
    }}
    @Test fun cancellationKeepsExistingTranscriptAndNotes()=runBlocking {fixture {processor,repo,fake->
        val old=repo.get("r")!!;fake.block=true;val job=processor.rediarize("r",null);fake.entered.await();processor.cancel("r");job.join()
        assertEquals(old.transcript,repo.get("r")!!.transcript);assertEquals(old.summary,repo.get("r")!!.summary);assertNull(repo.get("r")!!.speakerAnalysis)
    }}
    @Test fun deletionCancelsSpeakerWorkAndCannotResurrectRecord()=runBlocking {fixture {processor,repo,fake->
        fake.block=true;val job=processor.rediarize("r",null);fake.entered.await();processor.deleteRecording("r");job.join();assertNull(repo.get("r"))
    }}
    @Test fun concurrentTranscriptEditIsNotOverwritten()=runBlocking {fixture {processor,repo,fake->
        fake.block=true;val job=processor.rediarize("r",null);fake.entered.await()
        repo.update("r"){r->r.copy(transcript=r.transcript!!.copy(segments=r.transcript!!.segments.map{it.copy(text="并发修改")}))}
        fake.release.complete(Unit);job.join()
        assertEquals("并发修改",repo.get("r")!!.transcript!!.segments.single().text);assertTrue(repo.get("r")!!.error!!.startsWith("说话人重新计算"))
    }}
    @Test fun emptyAnalysisDoesNotEraseExistingAssignments()=runBlocking {fixture {processor,repo,fake->
        val before=repo.get("r")!!;fake.empty=true;processor.rediarize("r",null).join()
        assertEquals(before.transcript,repo.get("r")!!.transcript);assertEquals(before.summary,repo.get("r")!!.summary);assertNull(repo.get("r")!!.speakerAnalysis)
        assertTrue(repo.get("r")!!.error!!.contains("原结果已保留"))
    }}
    @Test fun rejectedRequestDoesNotMarkAnotherProcessingStageAsDone()=runBlocking {fixture {processor,repo,fake->
        for (stage in listOf(ProcessingStage.QUEUED,ProcessingStage.DIARIZING,ProcessingStage.TRANSCRIBING,ProcessingStage.SUMMARIZING)) {
            repo.update("r"){it.copy(stage=stage,stageText="原任务",progress=.4f)}
            processor.rediarize("r",null).join()
            assertEquals(stage,repo.get("r")!!.stage);assertEquals("原任务",repo.get("r")!!.stageText)
            assertEquals(.4f,repo.get("r")!!.progress);assertFalse(fake.entered.isCompleted)
        }
    }}
}
