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
import dev.soundsoftware.core.asr.*
import dev.soundsoftware.core.model.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    private fun optIn() {assumeTrue(args.getString("runPhoneQnn")=="true");check(QnnFiles.device().soc.startsWith("SM8850"));check(!QnnFiles.device().emulator)}
    private fun fixture():File {
        val name=args.getString("fixture") ?: "short-zh.wav"
        require(name in listOf("short-zh.wav","dialog.wav","dialog-repeat6.wav"));return File(root,name).also{check(it.isFile)}
    }
    private fun thermal()=context.getSystemService(PowerManager::class.java).currentThermalStatus
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun normalize(text:String)=text.lowercase().filter{it.isLetterOrDigit()}
    private fun distance(a:String,b:String):Int {
        var prev=IntArray(b.length+1){it}
        for(i in a.indices){val next=IntArray(b.length+1);next[0]=i+1;for(j in b.indices)next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j])0 else 1);prev=next};return prev[b.length]
    }
    @Test fun compareAsr()=runBlocking {
        optIn();val file=fixture();val wav=PcmWav.open(file);val samples=wav.readMono(0,wav.sampleCount.toInt())
        val repetitions=(args.getString("repetitions") ?: "3").toInt().also{require(it in 1..3)}
        val report=JSONObject().put("fixture",file.name).put("inputSha256",file.sha256()).put("durationMs",wav.durationMs)
            .put("soc",Build.SOC_MODEL).put("sdk",Build.VERSION.SDK_INT).put("appUid",android.os.Process.myUid())
            .put("windowSamples",160000).put("language","auto").put("itn",false).put("rows",JSONArray()).put("textComparisons",JSONArray())
        val output=File(root,"compare-${file.nameWithoutExtension}.json");fun save(){output.writeText(report.toString(2))}
        val decoded=mutableMapOf<Pair<Int,String>,String>()
        save()
        repeat(repetitions){rep->
            val order=if(rep%2==0)listOf("cpu","qnn") else listOf("qnn","cpu")
            for(profile in order){
                val row=JSONObject().put("repetition",rep).put("profile",profile).put("thermalBefore",thermal()).put("proof",JSONArray())
                val runtime:LocalAsrRuntime=if(profile=="cpu")SherpaAsrRuntime(SherpaRuntimeConfig(File(app.modelDir,"sensevoice"),SherpaModel.SENSE_VOICE,"2024-07-17-int8",maxChunkSeconds=10))
                else QnnProcessRuntime(QnnFiles.from(context,app.modelDir),File(context.cacheDir,"qnn-phone-windows"))
                val start=SystemClock.elapsedRealtimeNanos()
                try {
                    runtime.prepare();row.put("prepareMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
                    val chunks=JSONArray();val text=StringBuilder();var offset=0
                    while(offset<samples.size){val end=minOf(samples.size,offset+160000);val part=samples.copyOfRange(offset,end);val t=SystemClock.elapsedRealtimeNanos()
                        val result=runtime.decode(part,16000);text.append(result.text)
                        chunks.put(JSONObject().put("startSample",offset).put("endSample",end).put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-t)/1e6).put("characters",result.text.length));offset=end}
                    row.put("totalMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6).put("chunks",chunks).put("backend",runtime.backend)
                        .put("characters",text.length).put("textSha256",hash(text.toString())).put("status","passed")
                    if(profile=="qnn")assertEquals("qnn-htp",runtime.backend)
                    assertTrue(text.isNotBlank());decoded[rep to profile]=normalize(text.toString())
                }catch(e:Throwable){row.put("status","failed").put("error",e.message ?: e.javaClass.simpleName);throw e}
                finally {
                    if(runtime is QnnProcessRuntime){val stats=runtime.statistics
                        row.put("worker",JSONObject().put("starts",stats.processStarts).put("pid",stats.pid).put("context",stats.context).put("initializations",stats.modelInitializations).put("startupMs",stats.startupMs).put("modelLoadMs",stats.modelLoadMs))
                        stats.windows.forEach {window->row.getJSONArray("proof").put(JSONObject().put("pid",window.pid).put("context",window.context).put("sequence",window.sequence).put("graphExecuteSuccess",window.graphExecuted).put("inferenceMs",window.inferenceMs))}
                        if(row.optString("status")=="passed"){assertEquals(1,stats.processStarts);assertEquals(1,stats.modelInitializations);assertEquals(row.getJSONArray("chunks").length(),stats.windows.size)}
                    }
                    runtime.close();row.put("thermalAfter",thermal());report.getJSONArray("rows").put(row);save()
                }
                delay(1500)
            }
            val cpu=decoded.getValue(rep to "cpu");val qnn=decoded.getValue(rep to "qnn")
            report.getJSONArray("textComparisons").put(JSONObject().put("repetition",rep).put("normalizedEqual",cpu==qnn).put("cpuCharacters",cpu.length).put("qnnCharacters",qnn.length).put("characterEditDistance",distance(cpu,qnn)));save()
        }
        report.put("status","complete");save()
    }
    private suspend fun isolated(block:suspend(Processor,RecordingRepository)->Unit){
        val base=context;val prefix="qnn-phone-${UUID.randomUUID()}-";val wrapped=object:ContextWrapper(base){override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)}
        val directory=File(base.cacheDir,prefix).apply{mkdirs()};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {val repo=RecordingRepository(directory);val processor=Processor(repo,SettingsStore(wrapped),app.modelDir,File(directory,"checkpoints"),scope,qnnFiles=QnnFiles.from(base,app.modelDir),diarizer=SherpaDiarizer.forDevice(base,File(app.modelDir,"diarization"))){}
            block(processor,repo)
        }finally{scope.coroutineContext.job.cancelAndJoin();base.deleteSharedPreferences(prefix+"app-settings");directory.deleteRecursively()}
    }
    @Test fun compareProductionPipeline()=runBlocking {
        optIn();val input=fixture();val wav=PcmWav.open(input);val report=JSONObject().put("inputSha256",input.sha256()).put("durationMs",wav.durationMs).put("speakerCount",2).put("rows",JSONArray())
        val output=File(root,"pipeline-${input.nameWithoutExtension}.json")
        val repetitions=(args.getString("repetitions") ?: "1").toInt().also{require(it in 1..3)}
        repeat(repetitions){rep->
        val profiles=if(rep%2==0)listOf("sensevoice-cpu","sensevoice-qnn") else listOf("sensevoice-qnn","sensevoice-cpu")
        for(profile in profiles)isolated{processor,repo->
            input.copyTo(repo.audioFile("test"));repo.add(Recording("test",0,wav.durationMs))
            withContext(Dispatchers.Main.immediate){processor.start()}
            val stages=JSONArray();val start=SystemClock.elapsedRealtime();var last:ProcessingStage?=null
            val observer=launch(Dispatchers.Main.immediate){repo.recordings.collect{items->items.firstOrNull()?.let{r->if(r.stage!=last){last=r.stage;stages.put(JSONObject().put("stage",r.stage.name).put("offsetMs",SystemClock.elapsedRealtime()-start))}}}}
            val row=JSONObject().put("profile",profile).put("repetition",rep).put("thermalBefore",thermal())
            try {
                processor.enqueue("test",TranscribeOptions(template=SceneTemplate.TRANSCRIPT_ONLY,profileId=profile,speakerCount=2,mixedChineseEnglish=true))
                val done=withTimeout(600_000){repo.recordings.first{items->items.any{it.stage in listOf(ProcessingStage.DONE,ProcessingStage.FAILED)}}.single()}
                withTimeout(10_000){processor.busy.first{!it}}
                row.put("totalMs",SystemClock.elapsedRealtime()-start).put("stage",done.stage.name).put("error",done.error).put("stages",stages)
                    .put("backend",done.transcript?.backend).put("speakers",done.speakers.size).put("segments",done.transcript?.segments?.size ?: 0)
                    .put("textCharacters",done.transcript?.segments?.sumOf{it.text.length} ?: 0)
                assertEquals(done.error,ProcessingStage.DONE,done.stage);if(profile=="sensevoice-qnn")assertEquals("qnn-htp",done.transcript!!.backend)
                assertFalse(done.transcript!!.warnings.any{it.startsWith("说话人分离未完成")});row.put("status","passed")
            }finally{observer.cancelAndJoin();row.put("thermalAfter",thermal());report.getJSONArray("rows").put(row);output.writeText(report.toString(2))}
        }
        }
        report.put("status","complete");output.writeText(report.toString(2))
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
