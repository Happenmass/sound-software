package dev.soundsoftware.app.inference

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.transcript.*
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Local speaker diarization: pyannote segmentation-3.0 + 3D-Speaker embedding + fast clustering.
 * Model files live in `models/diarization/`, deployed from the global cache like the ASR models.
 */
data class DiarizationTimings(val threads:Int,val inputMs:Double,val loadMs:Double,val nativeMs:Double,
    val releaseMs:Double,val extraEmbeddingMs:Double,val clusteringMs:Double,val rawTurns:Int,val embeddings:Int)
data class DiarizationAnalysis(val turns: List<SpeakerTurn>, val warnings: List<String> = emptyList(),val timings:DiarizationTimings?=null,val backend:String="cpu-reference",val diagnostics:List<String> = emptyList(),val npu:SpeakerNpuEvidence?=null)

interface SpeakerAnalyzer {
    val isReady: Boolean
    suspend fun analyze(wav: PcmWav, speakerCount: Int?, onProgress: (Float) -> Unit): DiarizationAnalysis
}

class SherpaDiarizer(private val directory: File,private val numThreads:Int=defaultThreads(),private val worker:SpeakerWorkerConfig?=null,
    private val observeFeatures: ((List<SpeakerObservation>) -> Unit)? = null) : SpeakerAnalyzer {
    init {require(numThreads in 1..8)}
    val segmentationModel get() = File(directory, "segmentation.onnx")
    val embeddingModel get() = File(directory, "embedding.onnx")
    override val isReady get() = segmentationModel.length() > 0 && embeddingModel.length() > 0

    /** Whole-recording clustering keeps speaker ids consistent; bounded to keep memory predictable. */
    suspend fun diarize(wav: PcmWav, speakerCount: Int?, onProgress: (Float) -> Unit): List<SpeakerTurn> = analyze(wav, speakerCount, onProgress).turns

    override suspend fun analyze(wav: PcmWav, speakerCount: Int?, onProgress: (Float) -> Unit): DiarizationAnalysis {
        require(isReady) { "说话人分离模型未就绪" }
        require(wav.sampleRate == 16000 && wav.channels == 1)
        require(wav.durationMs <= MAX_DURATION_MS) { "录音超过 80 分钟，暂不做说话人分离" }
        val inputStart=System.nanoTime()
        fun ms(start:Long)=(System.nanoTime()-start)/1e6
        val samples = FloatArray(wav.sampleCount.toInt())
        var pos = 0L
        while (pos < wav.sampleCount) {
            currentCoroutineContext().ensureActive()
            val n = minOf(PcmWav.MAX_WINDOW_SAMPLES.toLong(), wav.sampleCount - pos).toInt()
            wav.readMono(pos, n).copyInto(samples, pos.toInt())
            pos += n
        }
        val inputMs=ms(inputStart)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentationModel.absolutePath), numThreads = numThreads),
            embedding = SpeakerEmbeddingExtractorConfig(model = embeddingModel.absolutePath, numThreads = numThreads),
            clustering = FastClusteringConfig(numClusters = speakerCount ?: -1, threshold = 0.5f),
            minDurationOn = 0.3f, minDurationOff = 0.5f,
        )
        val context=currentCoroutineContext();var loadMs=0.0;var nativeMs=0.0;var releaseMs=0.0
        val backendWarnings=mutableListOf<String>();var actualBackend="cpu-reference";var workerDiagnostics=emptyList<String>();var npuEvidence:SpeakerNpuEvidence?=null
        suspend fun reference():List<SpeakerTurn> {
            val start=System.nanoTime()
            val engine=try{OfflineSpeakerDiarization(config=config)}catch(e:LinkageError){error("当前设备无法加载说话人分离运行库")}
            loadMs=ms(start)
            return try {onProgress(0f);val begin=System.nanoTime();val segments=engine.process(samples);nativeMs=ms(begin);context.ensureActive()
                segments.mapNotNull {val a=(it.start*1000).toLong().coerceAtLeast(0);val b=minOf(wav.durationMs,(it.end*1000).toLong());if(b>a)SpeakerTurn(a,b,it.speaker) else null}
            }finally{val end=System.nanoTime();engine.release();releaseMs=ms(end)}
        }
        var workerResult:RawSpeakerAnalysis?=null
        var cleanTurns:List<CleanTurn>?=null
        fun planCleanTurns(turns:List<SpeakerTurn>)=turns.map { turn ->
            context.ensureActive()
            val ranges=cleanSpeechIntervals(turn,turns)
            val cleanMs=ranges.sumOf{it.last+1-it.first}
            var remaining=(minOf(cleanMs,6000)*16).toInt()
            val intervals=buildList {
                if(remaining>=4800) for(range in ranges) {
                    val start=(range.first*16).toInt().coerceIn(0,samples.size)
                    val end=((range.last+1)*16).toInt().coerceIn(start,samples.size)
                    val count=minOf(end-start,remaining)
                    if(count>0)add(start until start+count)
                    remaining-=count
                    if(remaining==0)break
                }
            }
            CleanTurn(cleanMs,if(remaining==0)intervals else emptyList())
        }
        val raw=if(worker!=null && worker.executable.canExecute() && wav.durationMs>=worker.minimumDurationMs) {
            try {onProgress(0f);val result=SpeakerRawWorker(worker,directory).analyze(wav,speakerCount) { turns ->
                    planCleanTurns(turns).also{cleanTurns=it}.map{it.intervals}
                }
                context.ensureActive()
                workerResult=result
                loadMs=result.loadMs;nativeMs=result.nativeMs;releaseMs=result.releaseMs;actualBackend=result.backend;workerDiagnostics=result.diagnostics;npuEvidence=result.npu;result.turns
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){cleanTurns=null;backendWarnings+=if(worker.npu==null)"并行说话人计算未完成，已使用兼容CPU重新分析" else "NPU说话人计算未完成，已使用本地CPU重新分析";reference()}
        }else reference()
        fun timings(extra:Double=0.0,cluster:Double=0.0,vectors:Int=0)=DiarizationTimings(numThreads,inputMs,loadMs,nativeMs,releaseMs,extra,cluster,raw.size,vectors)
        if(speakerCount != null || raw.isEmpty()) return DiarizationAnalysis(raw,backendWarnings,timings(),actualBackend,workerDiagnostics,npuEvidence)
        val extraStart=System.nanoTime()
        // Native 0.5/complete-link labels are conservative local fragments, not final identities.
        // Fit only >=2s clean turns, then assign other speech without creating phantom people.
        val plans=cleanTurns ?: planCleanTurns(raw)
        val htpVectors=workerResult?.cleanTurnVectors
        val extractor=if(htpVectors==null && plans.any{it.intervals.isNotEmpty()})
            SpeakerEmbeddingExtractor(config=SpeakerEmbeddingExtractorConfig(model=embeddingModel.absolutePath,numThreads=numThreads))
            else null
        val observations=try {
            raw.mapIndexed { index,turn ->
                context.ensureActive()
                val plan=plans[index]
                var vector=htpVectors?.get(index)
                if(extractor!=null && plan.intervals.isNotEmpty()) {
                    val audio=FloatArray(plan.intervals.sumOf{it.last+1-it.first})
                    var filled=0
                    for(interval in plan.intervals) {
                        samples.copyInto(audio,filled,interval.first,interval.last+1)
                        filled+=interval.last+1-interval.first
                    }
                    val stream=extractor.createStream()
                    try {stream.acceptWaveform(audio,16000);stream.inputFinished();if(extractor.isReady(stream))vector=extractor.compute(stream)}
                    finally {stream.release()}
                }
                onProgress((index+1).toFloat()/raw.size)
                SpeakerObservation(turn,vector,plan.cleanMs)
            }
        } finally {extractor?.release()}
        context.ensureActive()
        observeFeatures?.invoke(observations)
        val extraMs=ms(extraStart)+(workerResult?.cleanTurnMs ?: 0.0);val clusterStart=System.nanoTime()
        val result=SpeakerClustering().cluster(observations)
        val clusterMs=ms(clusterStart)
        val warnings=backendWarnings+buildList {
            if(result.anchorCount==0)add("干净语音不足，暂不能可靠估计说话人数；原文保留未归属说话人，可手动指定人数重试")
            else if(result.unknownTurns>0)add("部分短应答或噪声无法可靠归属说话人，已保留为未归属，未额外创建说话人")
        }
        return DiarizationAnalysis(result.turns,warnings,timings(extraMs,clusterMs,observations.count{it.embedding!=null}),actualBackend,workerDiagnostics,npuEvidence)
    }

    private data class CleanTurn(val cleanMs:Long,val intervals:List<IntRange>)

    // ponytail: whole recording in one float array (~230 MB/h); chunked clustering if longer meetings matter.
    companion object {
        const val MAX_DURATION_MS = 80L * 60 * 1000
        /** Only the measured device family uses the parallel worker; other devices keep JNI. */
        fun forDevice(context:android.content.Context,directory:File,observeFeatures: ((List<SpeakerObservation>) -> Unit)? = null):SherpaDiarizer {
            val native=File(context.applicationInfo.nativeLibraryDir)
            val supported=android.os.Build.VERSION.SDK_INT>=31 && android.os.Build.SOC_MODEL.startsWith("SM8850")
            val binary=File(native,"libsound_speaker_worker.so")
            // Use HTP whenever this device and its installed artifacts support it.
            val npuRuntime=SpeakerNpuRuntime(context,File(directory.parentFile,SpeakerNpuRuntime.MODEL_DIRECTORY),directory)
            val npuBinary=File(native,"libsound_speaker_npu_worker.so")
            val worker=if(dev.soundsoftware.app.BuildConfig.SPEAKER_NPU_ENABLED && supported && npuBinary.canExecute() && npuRuntime.isReady)SpeakerWorkerConfig(npuBinary,native,4,1,npu=npuRuntime)
                else if(dev.soundsoftware.app.BuildConfig.SPEAKER_WORKER_ENABLED && supported && binary.canExecute())SpeakerWorkerConfig(binary,native,4,1,minimumDurationMs=20_000) else null
            return SherpaDiarizer(directory,defaultThreads(),worker,observeFeatures)
        }
        // Measured on the supported SM8850 phone; retain the conservative setting elsewhere.
        private fun defaultThreads()=if(android.os.Build.VERSION.SDK_INT>=31 && android.os.Build.SOC_MODEL.startsWith("SM8850"))4 else 2
    }
}
