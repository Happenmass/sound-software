package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public/synthetic input only; no imports into the user's repository. */
@RunWith(AndroidJUnit4::class)
class SpeakerNpuValidationTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=base.applicationContext as SoundApplication
    private val root get()=File(base.getExternalFilesDir(null),"validation/speaker-npu").apply{mkdirs()}
    private fun enabled(){assumeTrue(InstrumentationRegistry.getArguments().getString("runSpeakerNpu")=="true")}
    @Test fun cpuReferenceAndNativeTimers()=runBlocking {
        enabled();val input=File(root,"dialog.wav");val wav=PcmWav.open(input);val report=JSONObject().put("inputSha256",input.sha256()).put("pid",android.os.Process.myPid()).put("rows",JSONArray());val output=File(root,"cpu-reference.json");output.writeText(report.toString(2))
        for(count in listOf<Int?>(2,null))repeat(3){rep->
            val start=android.os.SystemClock.elapsedRealtime();val result=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4).analyze(wav,count){}}
            val turns=JSONArray();result.turns.forEach{turns.put(JSONObject().put("startMs",it.startMs).put("endMs",it.endMs).put("speaker",it.speaker))}
            report.getJSONArray("rows").put(JSONObject().put("requestedCount",count ?: JSONObject.NULL).put("repetition",rep).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start).put("turns",turns).put("speakers",result.turns.map{it.speaker}.filter{it>=0}.distinct().size));output.writeText(report.toString(2));assertEquals(2,result.turns.map{it.speaker}.filter{it>=0}.distinct().size)
        }
        val samples=wav.readMono(0,wav.sampleCount.toInt());val cfg=OfflineSpeakerDiarizationConfig(segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(File(app.modelDir,"diarization/segmentation.onnx").path),numThreads=4,debug=true),embedding=SpeakerEmbeddingExtractorConfig(File(app.modelDir,"diarization/embedding.onnx").path,numThreads=4),clustering=FastClusteringConfig(numClusters=2,threshold=.5f),minDurationOn=.3f,minDurationOff=.5f)
        val engine=OfflineSpeakerDiarization(config=cfg);try{withContext(Dispatchers.Default){engine.process(samples)}}finally{engine.release()}
        report.put("status","complete");output.writeText(report.toString(2))
    }
    @Test fun compareParallelWithoutChangingAnyTurnOrIdentity()=runBlocking {
        enabled();val input=File(root,"dialog.wav");val wav=PcmWav.open(input)
        val reference=JSONObject(File(root,"cpu-reference.json").readText()).getJSONArray("rows")
        val native=File(base.applicationInfo.nativeLibraryDir);val testNative=File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.nativeLibraryDir)
        val binary=File(native,"libsound_speaker_worker.so").takeIf{it.canExecute()} ?: File(testNative,"libsound_speaker_worker.so")
        assertTrue(binary.canExecute())
        fun canonical(turns:List<dev.soundsoftware.core.transcript.SpeakerTurn>):List<Triple<Long,Long,Int>> {
            val ids=linkedMapOf<Int,Int>();return turns.sortedWith(compareBy({it.startMs},{it.endMs})).map{Triple(it.startMs,it.endMs,if(it.speaker<0)-1 else ids.getOrPut(it.speaker){ids.size})}
        }
        fun baseline(count:Int?):List<dev.soundsoftware.core.transcript.SpeakerTurn> {
            val row=(0 until reference.length()).map{reference.getJSONObject(it)}.first{if(count==null)it.isNull("requestedCount") else it.optInt("requestedCount")==count}
            val turns=row.getJSONArray("turns");return (0 until turns.length()).map{i->val t=turns.getJSONObject(i);dev.soundsoftware.core.transcript.SpeakerTurn(t.getLong("startMs"),t.getLong("endMs"),t.getInt("speaker"))}
        }
        val report=JSONObject().put("inputSha256",input.sha256()).put("rows",JSONArray());val output=File(root,"parallel-comparison.json")
        val variants=InstrumentationRegistry.getArguments().getString("variants") ?: "1x4,2x2,4x1"
        for(variant in variants.split(',')){val parts=variant.split('x').map{it.toInt()};val workers=parts[0];val intra=parts[1]
            for(count in listOf<Int?>(2,null)){
                val start=android.os.SystemClock.elapsedRealtime();val config=SpeakerWorkerConfig(binary,native,workers,intra,diagnostics=true)
                val result=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4,config).analyze(wav,count){}}
                val exact=canonical(baseline(count))==canonical(result.turns)
                val row=JSONObject().put("variant",variant).put("requestedCount",count ?: JSONObject.NULL).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start).put("backend",result.backend).put("exactTurnsAndAttribution",exact).put("speakers",result.turns.map{it.speaker}.filter{it>=0}.distinct().size).put("warnings",JSONArray(result.warnings)).put("timers",JSONArray(result.diagnostics))
                report.getJSONArray("rows").put(row);output.writeText(report.toString(2))
                assertEquals("cpu-parallel",result.backend);assertTrue("No fallback allowed in benchmark",result.warnings.none{it.startsWith("并行说话人")});assertEquals(2,row.getInt("speakers"));assertTrue("Every boundary and speaker assignment must match baseline (label permutation allowed)",exact)
            }
        }
        report.put("status","complete");output.writeText(report.toString(2))
    }

    @Test fun pairedSpeedAndControlQuality()=runBlocking {
        enabled()
        val native=File(base.applicationInfo.nativeLibraryDir)
        val binary=File(native,"libsound_speaker_worker.so").takeIf{it.canExecute()}
            ?: File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.nativeLibraryDir,"libsound_speaker_worker.so")
        assertTrue(binary.canExecute())
        fun canonical(turns:List<dev.soundsoftware.core.transcript.SpeakerTurn>):List<Triple<Long,Long,Int>> {
            val ids=linkedMapOf<Int,Int>()
            return turns.sortedWith(compareBy({it.startMs},{it.endMs})).map{Triple(it.startMs,it.endMs,if(it.speaker<0)-1 else ids.getOrPut(it.speaker){ids.size})}
        }
        fun thermal()=base.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
        val report=JSONObject().put("rows",JSONArray());val output=File(root,"paired-quality.json")
        val fixtures=listOf(Triple("dialog.wav",2,3),Triple("single.wav",1,1),Triple("three.wav",3,1))
        for((name,expected,repeats) in fixtures){val input=File(root,name);val wav=PcmWav.open(input)
            for(count in listOf<Int?>(expected,null))repeat(repeats){rep->
                val results=linkedMapOf<String,DiarizationAnalysis>()
                for(mode in if(rep%2==0)listOf("reference","parallel") else listOf("parallel","reference")){
                    val thermalBefore=thermal();val start=android.os.SystemClock.elapsedRealtime()
                    val config=if(mode=="parallel")SpeakerWorkerConfig(binary,native,4,1,diagnostics=true) else null
                    val result=withContext(Dispatchers.Default){SherpaDiarizer(File(app.modelDir,"diarization"),4,config).analyze(wav,count){}}
                    val elapsed=android.os.SystemClock.elapsedRealtime()-start;results[mode]=result
                    val t=result.timings!!
                    val turns=JSONArray();result.turns.forEach{turns.put(JSONArray(listOf(it.startMs,it.endMs,it.speaker)))}
                    val row=JSONObject().put("fixture",name).put("sha256",input.sha256()).put("requestedCount",count ?: JSONObject.NULL).put("repetition",rep)
                        .put("mode",mode).put("backend",result.backend).put("elapsedMs",elapsed).put("speakers",result.turns.map{it.speaker}.filter{it>=0}.distinct().size)
                        .put("thermalBefore",thermalBefore).put("thermalAfter",thermal()).put("turns",turns).put("warnings",JSONArray(result.warnings))
                        .put("loadMs",t.loadMs).put("nativeMs",t.nativeMs).put("extraEmbeddingMs",t.extraEmbeddingMs).put("timers",JSONArray(result.diagnostics))
                    report.getJSONArray("rows").put(row);output.writeText(report.toString(2))
                    assertEquals(expected,row.getInt("speakers"))
                    assertEquals(if(mode=="parallel")"cpu-parallel" else "cpu-reference",result.backend)
                    assertTrue(result.warnings.none{it.startsWith("并行说话人")})
                }
                val exact=canonical(results.getValue("reference").turns)==canonical(results.getValue("parallel").turns)
                report.put("lastPairExact",exact);output.writeText(report.toString(2))
                assertTrue("Every turn boundary and assignment must be unchanged for $name / $count",exact)
                assertEquals(results.getValue("reference").warnings,results.getValue("parallel").warnings)
            }
        }
        report.put("status","complete");output.writeText(report.toString(2))
    }

    @Test fun productionFactoryAndCpuFallbackPreserveResult()=runBlocking {
        enabled();val wav=PcmWav.open(File(root,"single.wav"));val directory=File(app.modelDir,"diarization")
        val expected=withContext(Dispatchers.Default){SherpaDiarizer(directory,4).analyze(wav,null){}}
        val production=withContext(Dispatchers.Default){SherpaDiarizer.forDevice(base,directory).analyze(wav,null){}}
        val hasHtp=dev.soundsoftware.app.BuildConfig.SPEAKER_NPU_ENABLED &&
            SpeakerNpuRuntime(base,File(app.modelDir,SpeakerNpuRuntime.MODEL_DIRECTORY),directory).isReady
        assertEquals(if(hasHtp)"qnn-htp-hybrid" else "cpu-reference",production.backend)
        if(hasHtp)assertEquals(1,production.npu?.cleanTurnExecutions)
        assertEquals(expected.turns,production.turns);assertEquals(expected.warnings,production.warnings)
        val longResult=withContext(Dispatchers.Default){SherpaDiarizer.forDevice(base,directory).analyze(PcmWav.open(File(root,"dialog.wav")),2){}}
        assertEquals(if(hasHtp)"qnn-htp-hybrid" else "cpu-parallel",longResult.backend);assertEquals(2,longResult.turns.map{it.speaker}.distinct().size)
        assertTrue(longResult.warnings.none{it.startsWith("并行说话人")})
        val broken=SpeakerWorkerConfig(File("/system/bin/false"),File(base.applicationInfo.nativeLibraryDir))
        assertTrue(broken.executable.canExecute())
        val fallback=withContext(Dispatchers.Default){SherpaDiarizer(directory,4,broken).analyze(wav,null){}}
        assertEquals("cpu-reference",fallback.backend);assertEquals(expected.turns,fallback.turns)
        assertTrue(fallback.warnings.any{it.startsWith("并行说话人计算未完成")})
    }

}
