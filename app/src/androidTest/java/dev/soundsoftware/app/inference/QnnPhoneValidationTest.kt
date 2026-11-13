package dev.soundsoftware.app.inference

import android.content.ContextWrapper
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import dev.soundsoftware.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit real-phone tests. Only public/synthetic fixtures; no user repository or remote calls. */
@RunWith(AndroidJUnit4::class)
class QnnPhoneValidationTest {
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as SoundApplication
    private val root get()=File(context.getExternalFilesDir(null),"validation/qnn-phone").apply{mkdirs()}
    private fun optIn() {assumeTrue(args.getString("runPhoneQnn")=="true");check(QnnDevice.current().supportsSm8850Htp)}
    private fun fixture():File {
        val name=args.getString("fixture") ?: "short-zh.wav"
        require(name in listOf("short-zh.wav","dialog.wav","dialog-repeat6.wav"));return File(root,name).also{check(it.isFile)}
    }
    private fun thermal()=context.getSystemService(PowerManager::class.java).currentThermalStatus
    private suspend fun isolated(block:suspend(Processor,RecordingRepository)->Unit){
        val base=context;val prefix="qnn-phone-${UUID.randomUUID()}-";val wrapped=object:ContextWrapper(base){override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)}
        val directory=File(base.cacheDir,prefix).apply{mkdirs()};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {val repo=RecordingRepository(directory);val processor=Processor(repo,SettingsStore(wrapped),app.modelDir,File(directory,"checkpoints"),scope,diarizer=SherpaDiarizer.forDevice(base,File(app.modelDir,"diarization")), qwenHtpFiles=QwenHtpFiles(base,app.modelDir)){}
            block(processor,repo)
        }finally{scope.coroutineContext.job.cancelAndJoin();base.deleteSharedPreferences(prefix+"app-settings");directory.deleteRecursively()}
    }
    @Test fun recalculateSpeakersPreservesEdits()=runBlocking {
        optIn();val input=fixture();val wav=PcmWav.open(input)
        isolated{processor,repo->
            input.copyTo(repo.audioFile("test"));val segments=(0..19).map{i->Segment("edited-$i","合成测试文字 $i",i*wav.durationMs/20,(i+1)*wav.durationMs/20,"old-${i%10}",true)}
            val old=Recording("test",0,wav.durationMs,title="保留的测试标题",titleEditedByUser=true,stage=ProcessingStage.DONE,
                transcript=Transcript(segments,"unchanged-asr","cpu","APPROXIMATE_SEGMENT",123),speakers=(0..9).map{Speaker("old-$it",it+1,"原命名$it")},summary=MeetingSummary(abstract="保留测试纪要",todos=listOf(TodoItem("t","已勾选测试任务",done=true))))
            repo.add(old);val report=JSONObject().put("inputSha256",input.sha256()).put("durationMs",wav.durationMs).put("initialSpeakers",10).put("rows",JSONArray());val output=File(root,"reanalysis-${input.nameWithoutExtension}.json")
            for(count in listOf<Int?>(null,2)){
                val start=SystemClock.elapsedRealtime();processor.rediarize("test",count).join();val saved=repo.get("test")!!
                val row=JSONObject().put("requestedCount",count ?: JSONObject.NULL).put("elapsedMs",SystemClock.elapsedRealtime()-start).put("speakers",saved.speakers.size).put("stage",saved.stage.name).put("error",saved.error)
                report.getJSONArray("rows").put(row);output.writeText(report.toString(2))
                assertNull(saved.error);assertNotNull(saved.speakerAnalysis);assertEquals(2,saved.speakers.size)
                assertEquals(old.transcript!!.segments.map{it.copy(speakerId=null)},saved.transcript!!.segments.map{it.copy(speakerId=null)})
                assertEquals(old.summary,saved.summary);assertEquals(old.title,saved.title);assertEquals(old.transcript!!.model,saved.transcript!!.model)
                row.put("textTimingEditsPreserved",true).put("summaryTodosTitlePreserved",true).put("asrNotRegenerated",true)
                output.writeText(report.toString(2))
            }
            report.put("status","complete");output.writeText(report.toString(2))
        }
    }
    @Test fun profileSpeakerStages()=runBlocking {
        optIn();val input=fixture();val wav=PcmWav.open(input)
        val report=JSONObject().put("inputSha256",input.sha256()).put("durationMs",wav.durationMs).put("rows",JSONArray())
        val output=File(root,"speaker-stages.json")
        val threadOptions=(args.getString("speakerThreads") ?: "2,4,6").split(',').map{it.toInt()}.also{require(it.size in 1..3 && it.all{n->n in 1..8})}
        for(threads in threadOptions) for(count in listOf<Int?>(2,null)) {
            val start=SystemClock.elapsedRealtime();val result=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),threads).analyze(wav,count){}}
            val t=result.timings!!
            val row=JSONObject().put("threads",threads).put("requestedCount",count ?: JSONObject.NULL).put("totalMs",SystemClock.elapsedRealtime()-start)
                .put("inputMs",t.inputMs).put("loadMs",t.loadMs).put("nativeMs",t.nativeMs).put("releaseMs",t.releaseMs)
                .put("extraEmbeddingMs",t.extraEmbeddingMs).put("clusteringMs",t.clusteringMs).put("rawTurns",t.rawTurns).put("embeddings",t.embeddings)
                .put("speakers",result.turns.map{it.speaker}.filter{it>=0}.distinct().size).put("thermalAfter",thermal())
            report.getJSONArray("rows").put(row);output.writeText(report.toString(2));assertEquals(2,row.getInt("speakers"));delay(1000)
        }
        report.put("status","complete");output.writeText(report.toString(2))
    }

}
