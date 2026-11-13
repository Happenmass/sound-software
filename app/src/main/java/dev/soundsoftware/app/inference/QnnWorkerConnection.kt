package dev.soundsoftware.app.inference

import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

interface QnnWorkerConnection : AutoCloseable {
    suspend fun send(line:String)
    suspend fun receive():String?
}
fun interface QnnWorkerFactory {
    fun start(command:List<String>,environment:Map<String,String>):QnnWorkerConnection
}

/** Bounded pipe transport. The recognizer lives in the child, not the app's CPU JNI process. */
class AndroidQnnWorkerConnection(command:List<String>,environment:Map<String,String>,private val label:String="QNN",private val maxLineChars:Int=65536):QnnWorkerConnection {
    private val process=try {ProcessBuilder(command).redirectErrorStream(true).apply{environment().putAll(environment)}.start()}
        catch(e:Exception){throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"无法启动${label} worker")}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lines=Channel<String>(64)
    private val writer=process.outputStream.bufferedWriter()
    val alive get()=process.isAlive
    val exitCode get()=if(process.isAlive)null else process.exitValue()
    init {
        scope.launch {
            try {process.inputStream.bufferedReader().use {reader->
                while(isActive){val line=reader.readLine() ?: break;check(line.length<=maxLineChars){"${label}响应过大"};lines.send(line)}
            };lines.close()}
            catch(e:Exception){lines.close(AsrException(ErrorCode.BACKEND_UNAVAILABLE,"${label} worker输出中断"))}
        }
    }
    override suspend fun send(line:String)=withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive();require(line.length<=8192 && '\n' !in line && '\r' !in line)
        try {writer.write(line);writer.newLine();writer.flush()}
        catch(e:java.io.IOException){currentCoroutineContext().ensureActive();throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"${label} worker已退出")}
    }
    override suspend fun receive():String?=lines.receiveCatching().let {result->result.exceptionOrNull()?.let{throw it};result.getOrNull()}
    override fun close() {
        scope.cancel()
        runCatching {writer.write("quit\n");writer.flush();process.waitFor(200,TimeUnit.MILLISECONDS)}
        if(process.isAlive)runCatching {process.destroyForcibly();process.waitFor(300,TimeUnit.MILLISECONDS)}
        runCatching {process.inputStream.close()};runCatching {writer.close()};lines.cancel()
    }
}
