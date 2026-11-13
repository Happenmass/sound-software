package dev.soundsoftware.app.inference

import dev.soundsoftware.core.asr.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*

data class NativeProcessResult(val exit:Int,val output:String)
fun interface QnnProcessRunner {suspend fun run(command:List<String>,environment:Map<String,String>):NativeProcessResult}
class AndroidQnnProcessRunner:QnnProcessRunner {
    override suspend fun run(command:List<String>,environment:Map<String,String>):NativeProcessResult=withContext(Dispatchers.IO) {
        val process=try{ProcessBuilder(command).redirectErrorStream(true).apply{environment().putAll(environment)}.start()}
            catch(e:Exception){throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"无法启动隔离QNN进程")}
        try { coroutineScope {
            val reader=async(Dispatchers.IO) {
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                try { while(true){val n=process.inputStream.read(buffer);if(n<0)break;check(out.size()+n<=2_000_000){"QNN输出过大"};out.write(buffer,0,n)} }
                catch(e:java.io.IOException) {currentCoroutineContext().ensureActive();throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN输出读取失败")}
                out.toString(Charsets.UTF_8.name())
            }
            try {withTimeout(90_000){while(process.isAlive){delay(40)};NativeProcessResult(process.exitValue(),reader.await())}}
            finally {reader.cancel();if(process.isAlive)process.destroyForcibly();runCatching{process.inputStream.close()};withContext(NonCancellable){reader.cancelAndJoin()}}
        } } catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN执行超时，进程已停止；未回退CPU")}
        finally {if(process.isAlive)process.destroyForcibly()}
    }
}
