package dev.soundsoftware.app.inference

import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.transcript.SpeakerTurn
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class SpeakerWorkerConfig(val executable:File,val nativeDir:File,val workers:Int=4,val intraThreads:Int=1,val diagnostics:Boolean=false,val minimumDurationMs:Long=0,val npu:SpeakerNpuRuntime?=null,val validateNpu:Boolean=false,val burst:Boolean=true,val profileHtp:Boolean=false) {
    init {require(workers in 1..4 && intraThreads in 1..4 && minimumDurationMs>=0)}
}
data class SpeakerNpuEvidence(val htpExecutions:Int,val validated:Int,val minCosine:Double,val maxAbsError:Double,val burst:Boolean,val embeddings:Int=0,val cleanTurnExecutions:Int=0)
data class RawSpeakerAnalysis(val turns:List<SpeakerTurn>,val loadMs:Double,val nativeMs:Double,val releaseMs:Double,val diagnostics:List<String>,val backend:String="cpu-parallel",val npu:SpeakerNpuEvidence?=null,val cleanTurnVectors:List<FloatArray?>?=null,val cleanTurnMs:Double=0.0)

/** Isolated speaker worker: CPU or explicitly evidenced HTP/CPU hybrid with pinned weights. */
class SpeakerRawWorker(private val config:SpeakerWorkerConfig,private val modelDir:File) {
    suspend fun analyze(
        wav:PcmWav,count:Int?,cleanInputs:((List<SpeakerTurn>)->List<List<IntRange>>)?=null,
    ):RawSpeakerAnalysis=withContext(Dispatchers.IO) {
        require(count==null || count in 1..12);require(wav.sampleRate==16000 && wav.channels==1)
        require(wav.file.path.none{it=='\t' || it=='\n' || it=='\r'})
        val command=listOf(config.executable.path,"--parallel-workers=${config.workers}",
            "--segmentation.pyannote-model=${File(modelDir,"segmentation.onnx").path}","--segmentation.num-threads=${config.intraThreads}","--segmentation.debug=${config.diagnostics}","--segmentation.provider=cpu",
            "--embedding.model=${File(modelDir,"embedding.onnx").path}","--embedding.num-threads=${config.intraThreads}","--embedding.provider=cpu",
            "--clustering.cluster-threshold=0.5","--min-duration-on=0.3","--min-duration-off=0.5")
        val backend=if(config.npu==null)"cpu-parallel" else "qnn-htp-hybrid"
        config.npu?.prepare()
        val environment=mutableMapOf("LD_LIBRARY_PATH" to "${config.executable.parent}:${config.nativeDir.path}:/system/lib64:/system_ext/lib64:/vendor/lib64")
        config.npu?.let {
            environment["LD_LIBRARY_PATH"]="${it.runtimeDir.path}:"+environment.getValue("LD_LIBRARY_PATH")
            environment["ADSP_LIBRARY_PATH"]=it.runtimeDir.path
            environment["SOUND_SPEAKER_NPU_RUNTIME"]=it.runtimeDir.path
            environment["SOUND_SPEAKER_NPU_MODELS"]=it.modelDir.path
            if(config.validateNpu)environment["SOUND_SPEAKER_NPU_VALIDATE"]="1"
            if(config.profileHtp)environment["SOUND_SPEAKER_NPU_PROFILE"]="1"
            if(config.burst)environment["SOUND_SPEAKER_NPU_BURST"]="1"
        }
        val connection=AndroidQnnWorkerConnection(command,environment,label="说话人",maxLineChars=2_000_000)
        var evidence:SpeakerNpuEvidence?=null;var htpBackend=false
        val diagnostics=mutableListOf<String>()
        suspend fun frame():JsonObject {
            var size=0
            repeat(4096){val line=connection.receive() ?: throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"说话人worker已退出")
                size+=line.length;check(size<=2_000_000){"说话人worker输出过大"}
                if(line.startsWith("SOUND_DIAR "))return Json.parseToJsonElement(line.removePrefix("SOUND_DIAR ")).jsonObject
                if(line.trim()=="backendId: 6")htpBackend=true
                if(line.startsWith("SOUND_NPU_STATS ")) {
                    val obj=Json.parseToJsonElement(line.removePrefix("SOUND_NPU_STATS ")).jsonObject
                    check(obj.getValue("cpuSubstitutions").jsonPrimitive.int==0)
                    evidence=SpeakerNpuEvidence(obj.getValue("htpExecutions").jsonPrimitive.int,obj.getValue("validated").jsonPrimitive.int,obj.getValue("minCosine").jsonPrimitive.double,obj.getValue("maxAbsError").jsonPrimitive.double,obj["burst"]?.jsonPrimitive?.boolean ?: false,obj.getValue("embeddings").jsonPrimitive.int)
                }
                diagnostics+=line
            };error("说话人worker没有响应帧")
        }
        var load=0.0;var elapsed=0.0;var release=0.0;var turns=emptyList<SpeakerTurn>()
        var cleanVectors:List<FloatArray?>?=null;var cleanMs=0.0
        try {withTimeout(maxOf(90_000,wav.durationMs*2)) {
            val ready=frame();check(ready["type"]?.jsonPrimitive?.content=="ready" && ready["protocol"]?.jsonPrimitive?.int==1 && ready["backend"]?.jsonPrimitive?.content==backend)
            val pid=ready.getValue("pid").jsonPrimitive.int;check(pid>0 && ready["workers"]?.jsonPrimitive?.int==config.workers)
            load=ready.getValue("loadMs").jsonPrimitive.double
            connection.send("1\t${count ?: 0}\t${wav.file.absolutePath}")
            val result=frame();check(result["type"]?.jsonPrimitive?.content=="result" && result["protocol"]?.jsonPrimitive?.int==1 && result["backend"]?.jsonPrimitive?.content==backend && result["pid"]?.jsonPrimitive?.int==pid && result["sequence"]?.jsonPrimitive?.int==1)
            elapsed=result.getValue("elapsedMs").jsonPrimitive.double;check(load.isFinite() && load>=0 && elapsed.isFinite() && elapsed>=0)
            turns=result.getValue("turns").jsonArray.map {element->val a=element.jsonArray;check(a.size==3)
                val start=a[0].jsonPrimitive.long.coerceAtLeast(0);val end=minOf(wav.durationMs,a[1].jsonPrimitive.long);val speaker=a[2].jsonPrimitive.int
                check(start>=0 && end>start && speaker>=0);SpeakerTurn(start,end,speaker)
            }
            if(config.npu!=null) {
                val firstPass=checkNotNull(evidence){"缺少NPU执行证据"}
                if(count==null && cleanInputs!=null) {
                    val started=System.nanoTime()
                    val inputs=cleanInputs(turns)
                    val vectors=MutableList<FloatArray?>(inputs.size){null}
                    inputs.indices.filter{inputs[it].isNotEmpty()}.chunked(4).forEach { batch ->
                        val request=batch.joinToString("\t") { index ->
                            inputs[index].joinToString(" ") { "${it.first} ${it.last+1}" }
                        }
                        val before=checkNotNull(evidence)
                        evidence=null
                        connection.send("embed\t$request")
                        val response=frame()
                        check(response.getValue("type").jsonPrimitive.content=="embeddings")
                        val returned=response.getValue("vectors").jsonArray
                        check(returned.size==batch.size)
                        var populated=0
                        returned.forEachIndexed { slot,element ->
                            if(element!=JsonNull) {
                                val values=element.jsonArray
                                check(values.size==192)
                                vectors[batch[slot]]=FloatArray(values.size){values[it].jsonPrimitive.float.also{value->check(value.isFinite())}}
                                populated++
                            }
                        }
                        val after=checkNotNull(evidence){"缺少第二轮NPU执行证据"}
                        check(after.embeddings-before.embeddings==populated && after.htpExecutions-before.htpExecutions==populated)
                    }
                    cleanVectors=vectors
                    cleanMs=(System.nanoTime()-started)/1e6
                }
                val proof=checkNotNull(evidence)
                check(proof.htpExecutions==0 || htpBackend){"未执行HTP声纹图"}
                if(config.validateNpu)check(proof.validated==proof.embeddings && proof.minCosine.isFinite() && proof.minCosine>=.9999){"NPU声纹数值校验未通过"}
                evidence=proof.copy(cleanTurnExecutions=proof.htpExecutions-firstPass.htpExecutions)
            }
        }} catch(e:TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"说话人worker超时")
        } finally {val start=System.nanoTime();connection.close();release=(System.nanoTime()-start)/1e6}
        RawSpeakerAnalysis(turns,load,elapsed,release,diagnostics.filter{it.contains("OfflineSpeakerDiarization:") || it.startsWith("SOUND_SPEAKER_PROFILE ")},if(config.npu!=null && evidence?.htpExecutions==0)"cpu-no-embedding" else backend,evidence,cleanVectors,cleanMs)
    }
}
