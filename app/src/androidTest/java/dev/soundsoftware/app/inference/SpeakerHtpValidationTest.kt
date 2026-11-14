package dev.soundsoftware.app.inference

import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import dev.soundsoftware.core.transcript.SpeakerTurn
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTP proof, output equivalence and public/synthetic fixtures. Never touches user recordings. */
@RunWith(AndroidJUnit4::class)
class SpeakerHtpValidationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as SoundApplication
    private val args get()=InstrumentationRegistry.getArguments()
    private val root get()=File(context.getExternalFilesDir(null),"validation/speaker-npu-v2").apply{mkdirs()}
    private fun enabled(){assumeTrue(args.getString("runSpeakerHtp")=="true")}
    private fun canonical(turns:List<SpeakerTurn>):List<Triple<Long,Long,Int>> {
        val ids=linkedMapOf<Int,Int>()
        return turns.sortedWith(compareBy({it.startMs},{it.endMs})).map{Triple(it.startMs,it.endMs,if(it.speaker<0)-1 else ids.getOrPut(it.speaker){ids.size})}
    }
    private fun configuration(npu:Boolean,validate:Boolean=false):SpeakerWorkerConfig {
        val native=File(context.applicationInfo.nativeLibraryDir)
        return SpeakerWorkerConfig(File(native,if(npu)"libsound_speaker_npu_worker.so" else "libsound_speaker_worker.so"),native,4,1,diagnostics=true,
            npu=if(npu)SpeakerNpuRuntime(context,File(app.modelDir,SpeakerNpuRuntime.MODEL_DIRECTORY),File(app.modelDir,"diarization")) else null,validateNpu=validate)
    }
    private suspend fun analyze(file:File,count:Int?,npu:Boolean,validate:Boolean=false)=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4,configuration(npu,validate)).analyze(PcmWav.open(file),count){}}
    private fun row(file:File,count:Int?,mode:String,result:DiarizationAnalysis,elapsed:Long,thermalBefore:Int):JSONObject {
        val turns=JSONArray();result.turns.forEach{turns.put(JSONArray(listOf(it.startMs,it.endMs,it.speaker)))}
        val t=result.timings!!
        return JSONObject().put("fixture",file.name).put("inputSha256",file.sha256()).put("requestedCount",count ?: JSONObject.NULL).put("mode",mode).put("backend",result.backend)
            .put("elapsedMs",elapsed).put("speakers",result.turns.map{it.speaker}.filter{it>=0}.distinct().size).put("turns",turns).put("warnings",JSONArray(result.warnings))
            .put("nativeMs",t.nativeMs).put("loadMs",t.loadMs).put("extraEmbeddingMs",t.extraEmbeddingMs)
            .put("thermalBefore",thermalBefore).put("thermalAfter",context.getSystemService(PowerManager::class.java).currentThermalStatus).apply {
                result.npu?.let {put("htpExecutions",it.htpExecutions).put("htpEmbeddings",it.embeddings).put("cleanTurnHtpExecutions",it.cleanTurnExecutions).put("validatedEmbeddings",it.validated).put("minEmbeddingCosine",it.minCosine).put("maxEmbeddingAbsError",it.maxAbsError).put("burst",it.burst)}
            }
    }
    @Test fun numericQualityAndOneTwoThreeSpeakerControls()=runBlocking {
        enabled();val report=JSONObject().put("rows",JSONArray());val output=File(root,"quality.json")
        for((name,expected) in listOf("dialog.wav" to 2,"single.wav" to 1,"single-long.wav" to 1,"three.wav" to 3))for(count in listOf<Int?>(expected,null)) {
            val input=File(root,name);val results=linkedMapOf<String,DiarizationAnalysis>()
            for(mode in listOf("cpu","npu-validated")) {
                val thermal=context.getSystemService(PowerManager::class.java).currentThermalStatus;val start=SystemClock.elapsedRealtime()
                val result=analyze(input,count,mode!="cpu",mode!="cpu");results[mode]=result
                report.getJSONArray("rows").put(row(input,count,mode,result,SystemClock.elapsedRealtime()-start,thermal));output.writeText(report.toString(2))
                assertEquals(if(mode=="cpu")"cpu-parallel" else if(result.npu?.htpExecutions==0)"cpu-no-embedding" else "qnn-htp-hybrid",result.backend)
                assertEquals(expected,result.turns.map{it.speaker}.filter{it>=0}.distinct().size)
                assertTrue(result.warnings.none{it.startsWith("NPU") || it.startsWith("并行说话人")})
                if(mode!="cpu"){val proof=result.npu!!;if(name!="single.wav")assertTrue(proof.htpExecutions>0);assertEquals(proof.embeddings,proof.validated);assertTrue(proof.minCosine>=.9999);if(proof.htpExecutions>0)assertTrue(proof.burst)}
            }
            val exact=canonical(results.getValue("cpu").turns)==canonical(results.getValue("npu-validated").turns)
            report.put("lastPairExact",exact);output.writeText(report.toString(2));assertTrue("Every boundary and assignment must match: $name / $count",exact)
            assertEquals(results.getValue("cpu").warnings,results.getValue("npu-validated").warnings)
        }
        report.put("status","complete");output.writeText(report.toString(2))
    }
    @Test fun pairedCpuNpuSpeedAndAttribution()=runBlocking {
        enabled();val file=File(root,"dialog.wav");val report=JSONObject().put("rows",JSONArray());val output=File(root,"paired-speed.json")
        val repetitions=(args.getString("repetitions") ?: "3").toInt().also{require(it in 1..3)}
        for(count in listOf<Int?>(2,null))repeat(repetitions){rep->
            val results=linkedMapOf<String,DiarizationAnalysis>()
            for(mode in if(rep%2==0)listOf("cpu","npu") else listOf("npu","cpu")) {
                val thermal=context.getSystemService(PowerManager::class.java).currentThermalStatus;val start=SystemClock.elapsedRealtime()
                val result=analyze(file,count,mode=="npu");results[mode]=result
                report.getJSONArray("rows").put(row(file,count,mode,result,SystemClock.elapsedRealtime()-start,thermal).put("repetition",rep));output.writeText(report.toString(2))
                assertEquals(if(mode=="cpu")"cpu-parallel" else "qnn-htp-hybrid",result.backend);assertEquals(2,result.turns.map{it.speaker}.filter{it>=0}.distinct().size)
                if(mode=="npu"){assertTrue(result.npu!!.htpExecutions>0);assertEquals(0,result.npu!!.validated);assertTrue(result.npu!!.burst);assertEquals(if(count==null)6 else 0,result.npu!!.cleanTurnExecutions)}
            }
            val exact=canonical(results.getValue("cpu").turns)==canonical(results.getValue("npu").turns)
            report.put("lastPairExact",exact);output.writeText(report.toString(2));assertTrue(exact);assertEquals(results.getValue("cpu").warnings,results.getValue("npu").warnings)
        }
        report.put("status","complete");output.writeText(report.toString(2))
    }
    @Test fun sharedGraphSpeedAndAttribution()=runBlocking {
        enabled()
        val file=File(root,"dialog.wav")
        assertEquals("46d10e76f54f3249b2628148beb542c5b7f30c04471d392a302fa3217068c2a7",file.sha256())
        val wav=PcmWav.open(file)
        val diarizer=SherpaDiarizer.forDevice(context,File(app.modelDir,"diarization"))
        val thermal=context.getSystemService(PowerManager::class.java).currentThermalStatus
        val started=SystemClock.elapsedRealtimeNanos()
        val result=withContext(Dispatchers.Default){diarizer.analyze(wav,null){}}
        val wallMs=(SystemClock.elapsedRealtimeNanos()-started)/1e6
        val entry=row(file,null,"production",result,wallMs.toLong(),thermal).put("wallMs",wallMs)
        File(root,"shared-graph.json").writeText(entry.toString(2))
        val proof=checkNotNull(result.npu)
        val cleanCalls=6
        assertEquals("qnn-htp-hybrid",result.backend)
        assertEquals(71,proof.htpExecutions-proof.cleanTurnExecutions)
        assertEquals(cleanCalls,proof.cleanTurnExecutions)
        assertEquals(77,proof.embeddings)
        assertEquals(0,proof.validated)
        assertEquals(emptyList<String>(),result.warnings)
        assertEquals(listOf(
            Triple(655L,8316L,0),Triple(8940L,14864L,1),Triple(16028L,22525L,0),
            Triple(23149L,29899L,1),Triple(31030L,39029L,0),Triple(39552L,43973L,1),
        ),canonical(result.turns))
    }
    @Test fun actualNpuCancellationAndExplicitLocalFallback()=runBlocking {
        enabled();val input=PcmWav.open(File(root,"dialog.wav"))
        val job=launch(Dispatchers.Default){SpeakerRawWorker(configuration(true),File(app.modelDir,"diarization")).analyze(input,2)}
        delay(1800);withTimeout(4000){job.cancelAndJoin()};assertTrue(job.isCancelled)
        val wav=PcmWav.open(File(root,"single.wav"));val expected=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4).analyze(wav,null){}}
        val invalid=configuration(true).copy(npu=SpeakerNpuRuntime(context,File(root,"nonexistent-models"),File(app.modelDir,"diarization")))
        val actual=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4,invalid).analyze(wav,null){}}
        assertEquals("cpu-reference",actual.backend);assertEquals(expected.turns,actual.turns);assertTrue(actual.warnings.any{it.startsWith("NPU说话人计算未完成")})
    }
}
