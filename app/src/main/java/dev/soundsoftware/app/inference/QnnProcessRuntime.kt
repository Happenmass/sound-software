package dev.soundsoftware.app.inference

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.core.asr.*
import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import kotlinx.coroutines.*

data class QnnDevice(val soc:String,val arm64:Boolean,val emulator:Boolean,val pageSize:Long)
data class QnnFiles(val nativeDir:File,val modelDir:File) {
    val executable get()=File(nativeDir,"libsound_qnn_runner.so")
    val model get()=File(modelDir,"model.bin")
    val tokens get()=File(modelDir,"tokens.txt")
    fun unavailable(device:QnnDevice):String? = when {
        !BuildConfig.QNN_ENABLED -> "此安装包未包含QNN运行组件"
        device.emulator -> "模拟器没有高通 NPU，无法使用此加速方式"
        !device.arm64 || !device.soc.uppercase().startsWith("SM8850") -> "当前QNN模型仅匹配SM8850，检测到${device.soc.ifBlank{"未知芯片"}}"
        device.pageSize!=4096L -> "当前QNN组合仅验证4KB内存页设备"
        !executable.canExecute() -> "QNN子进程组件未安装"
        listOf("libQnnHtp.so","libQnnSystem.so","libQnnHtpPrepare.so","libQnnHtpV81Stub.so","libQnnHtpV81Skel.so").any{!File(nativeDir,it).isFile} -> "QNN运行库不完整"
        model.length()==0L || tokens.length()==0L -> "请部署SM8850专用10秒SenseVoice QNN模型"
        else -> null
    }
    companion object {
        fun from(context:Context,modelDir:File)=QnnFiles(File(context.applicationInfo.nativeLibraryDir),File(modelDir,"sensevoice-qnn-sm8850"))
        fun device()=QnnDevice(if(Build.VERSION.SDK_INT>=31)Build.SOC_MODEL else "",Build.SUPPORTED_ABIS.contains("arm64-v8a"),
            Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.startsWith("generic"),Os.sysconf(OsConstants._SC_PAGESIZE))
    }
}
data class QnnWindowStats(val sequence:Int,val pid:Int,val context:Int,val inferenceMs:Double,val graphExecuted:Boolean=true)
data class QnnRuntimeStats(val processStarts:Int,val pid:Int?,val context:Int?,val modelInitializations:Int,val startupMs:Double,val modelLoadMs:Double,val windows:List<QnnWindowStats>)

/** One child/recognizer per runtime (one transcription); all windows reuse its context. */
class QnnProcessRuntime(private val files:QnnFiles,private val cache:File,private val device:QnnDevice=QnnFiles.device(),
    private val language:String?=null,
    private val factory:QnnWorkerFactory=QnnWorkerFactory {command,env->AndroidQnnWorkerConnection(command,env)}):LocalAsrRuntime {
    override val modelId="SenseVoice-QNN-SM8850-10s-int8-2024-07-17:qnn2.40:sherpa2.0.1:worker1"
    override val runtimeName="sherpa-qnn-persistent-worker"
    override val maxSamples=QnnContract.MAX_SAMPLES
    private var prepared=false;private var closed=false;private var failed=false;private var silent=false
    private var worker:QnnWorkerConnection?=null;private var ready:QnnWorkerReady?=null
    private var starts=0;private var startupMs=0.0;private var sequence=0
    private val windows=mutableListOf<QnnWindowStats>()
    private val execution=kotlinx.coroutines.sync.Mutex()
    val statistics get()=QnnRuntimeStats(starts,ready?.pid,ready?.context,if(ready!=null)1 else 0,startupMs,ready?.loadMs ?: 0.0,windows.toList())
    override val backend get()=if(windows.isNotEmpty())"qnn-htp" else if(silent)"qnn-not-executed-silence" else "qnn-htp-not-executed"
    override suspend fun prepare() {
        currentCoroutineContext().ensureActive();check(!closed && !failed)
        files.unavailable(device)?.let{throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,it)}
        cache.mkdirs();prepared=true
    }
    private suspend fun frame():Pair<String,String> {
        val diagnostics=StringBuilder()
        repeat(4096) {
            val line=worker!!.receive() ?: throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN worker意外退出；未回退CPU")
            if(line.startsWith(QnnContract.FRAME_PREFIX))return line.removePrefix(QnnContract.FRAME_PREFIX) to diagnostics.toString()
            check(diagnostics.length+line.length<=2_000_000){"QNN诊断输出过大"};diagnostics.append(line).append('\n')
        }
        throw AsrException(ErrorCode.INVALID_RESPONSE,"QNN响应缺少协议帧")
    }
    private suspend fun startWorker() {
        val command=listOf(files.executable.absolutePath,"--provider=qnn","--debug=true","--num-threads=2","--sense-voice-use-itn=false",
            "--sense-voice-language=${language ?: "auto"}","--tokens=${files.tokens.absolutePath}",
            "--sense-voice.qnn-context-binary=${files.model.absolutePath}","--sense-voice.qnn-backend-lib=${File(files.nativeDir,"libQnnHtp.so").absolutePath}",
            "--sense-voice.qnn-system-lib=${File(files.nativeDir,"libQnnSystem.so").absolutePath}")
        val begin=System.nanoTime()
        worker=factory.start(command,mapOf("LD_LIBRARY_PATH" to "${files.nativeDir.absolutePath}:/system/lib64:/system_ext/lib64:/vendor/lib64","ADSP_LIBRARY_PATH" to files.nativeDir.absolutePath));starts++
        val (json,diagnostics)=withTimeout(90_000){frame()}
        ready=QnnContract.parseWorkerReady(json,diagnostics);startupMs=(System.nanoTime()-begin)/1e6
    }
    override suspend fun decode(samples:FloatArray,sampleRate:Int):LocalRecognition {
        require(sampleRate==16000);QnnContract.requireWindow(samples.size)
        execution.lock()
        try {
            check(prepared && !closed && !failed){"QNN尚未准备或任务已结束"};currentCoroutineContext().ensureActive()
            if(samples.all{it==0f}){silent=true;return LocalRecognition("")}
            val input=File.createTempFile("qnn-window-",".wav",cache)
            try {
                if(worker==null)startWorker()
                PcmWav.writeMono(input,samples,16000)
                val request=++sequence
                val (json,diagnostics)=withTimeout(90_000){worker!!.send("$request\t${input.absolutePath}");frame()}
                val result=QnnContract.parseWorkerReply(json,diagnostics,ready!!,request)
                currentCoroutineContext().ensureActive()
                windows+=QnnWindowStats(request,ready!!.pid,ready!!.context,result.inferenceMs)
                return LocalRecognition(result.text)
            } finally {input.delete()}
        } catch(e:Exception) {
            failed=true;worker?.close();worker=null
            if(e is TimeoutCancellationException){currentCoroutineContext().ensureActive();throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN worker超时，已停止；未回退CPU")}
            throw e
        } finally {execution.unlock()}
    }
    override fun close(){closed=true;prepared=false;worker?.close();worker=null}
}
