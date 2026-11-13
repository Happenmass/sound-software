package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.core.asr.*
import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QnnProcessRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(run:suspend(QnnFiles,File)->Unit) {
        assumeTrue("QNN build only", BuildConfig.QNN_ENABLED)
        val dir=File(context.cacheDir,"qnn-${UUID.randomUUID()}").apply{mkdirs()};val lib=File(dir,"native").apply{mkdirs()};val model=File(dir,"model").apply{mkdirs()}
        for(n in listOf("libsound_qnn_runner.so","libQnnHtp.so","libQnnSystem.so","libQnnHtpPrepare.so","libQnnHtpV81Stub.so","libQnnHtpV81Skel.so"))File(lib,n).apply{writeText("fixture");setExecutable(true)}
        File(model,"model.bin").writeText("synthetic");File(model,"tokens.txt").writeText("synthetic")
        try{run(QnnFiles(lib,model),dir)}finally{dir.deleteRecursively()}
    }
    private val device=QnnDevice("SM8850",true,false,4096)
    @Test fun emulatorIsRejectedBeforeAnyNpuLaunch()=runBlocking {
        val files=QnnFiles.from(context,File(context.getExternalFilesDir(null),"models"))
        val emulator=QnnFiles.device().copy(emulator=true)
        assertNotNull(files.unavailable(emulator))
        val runtime=QnnProcessRuntime(files,context.cacheDir,emulator)
        assertTrue(runCatching{runtime.prepare()}.isFailure)
    }

    private class FakeConnection(private val evidenceEveryWindow:Boolean=true,private val block:Boolean=false):QnnWorkerConnection {
        val lines=kotlinx.coroutines.channels.Channel<String>(16);var sequence=0;var closed=false
        init {lines.trySend("loaded libQnnHtp.so");lines.trySend("backendId: 6");lines.trySend("SOUND_QNN {\"type\":\"ready\",\"protocol\":1,\"pid\":123,\"context\":1,\"initializations\":1,\"loadMs\":10}")}
        override suspend fun send(line:String){
            sequence++;assertTrue(line.startsWith("$sequence\t"));if(block)return
            if(evidenceEveryWindow || sequence==1)lines.send("QNN_VALIDATION graphExecute succeeded")
            lines.send("SOUND_QNN {\"type\":\"result\",\"protocol\":1,\"pid\":123,\"context\":1,\"initializations\":1,\"sequence\":$sequence,\"request\":$sequence,\"inferenceMs\":1,\"result\":{\"text\":\"合成文本\"}}")
        }
        override suspend fun receive()=lines.receiveCatching().getOrNull()
        override fun close(){closed=true;lines.cancel()}
    }
    @Test fun guardedAdapterReusesWorkerAndContextForEveryWindow()=runBlocking {fixture {files,dir->
        var starts=0;val connection=FakeConnection()
        val factory=QnnWorkerFactory {command,_->starts++;assertTrue(command.contains("--provider=qnn"));connection}
        val runtime=QnnProcessRuntime(files,File(dir,"windows"),device,factory=factory);runtime.prepare()
        assertEquals("",runtime.decode(FloatArray(1000),16000).text);assertEquals(0,starts)
        repeat(2){assertEquals("合成文本",runtime.decode(FloatArray(1000){.1f},16000).text)}
        assertEquals("qnn-htp",runtime.backend);assertEquals(1,starts);assertEquals(1,runtime.statistics.modelInitializations)
        assertEquals(listOf(123,123),runtime.statistics.windows.map{it.pid});assertEquals(2,runtime.statistics.windows.size)
        assertTrue(runCatching{runtime.decode(FloatArray(160001),16000)}.isFailure);assertEquals(1,starts)
        assertTrue(File(dir,"windows").listFiles().orEmpty().isEmpty());runtime.close();assertTrue(connection.closed)
    }}
    @Test fun missingNewGraphEvidenceCannotReusePreviousWindowProofOrRestart()=runBlocking {fixture {files,dir->
        var starts=0;val connection=FakeConnection(evidenceEveryWindow=false)
        val runtime=QnnProcessRuntime(files,dir,device,factory=QnnWorkerFactory{_,_->starts++;connection});runtime.prepare()
        runtime.decode(FloatArray(1000){.1f},16000)
        assertTrue(runCatching{runtime.decode(FloatArray(1000){.1f},16000)}.isFailure);assertTrue(connection.closed)
        assertTrue(runCatching{runtime.decode(FloatArray(1000){.1f},16000)}.isFailure);assertEquals(1,starts)
    }}
    @Test fun cancellationClosesWorkerAndCleansCurrentWave()=runBlocking {fixture {files,dir->
        val connection=FakeConnection(block=true);val runtime=QnnProcessRuntime(files,File(dir,"windows"),device,factory=QnnWorkerFactory{_,_->connection});runtime.prepare()
        val job=launch {runtime.decode(FloatArray(1000){.1f},16000)}
        withTimeout(3000){while(connection.sequence==0)delay(10);job.cancelAndJoin()}
        assertTrue(connection.closed);assertTrue(File(dir,"windows").listFiles().orEmpty().isEmpty())
    }}
    @Test fun packagedExecutableRunsUnderAppUidAndBadBackendCannotCrashApp()=runBlocking {
        assumeTrue("QNN build only", BuildConfig.QNN_ENABLED)
        val native=File(context.applicationInfo.nativeLibraryDir);val executable=File(native,"libsound_qnn_runner.so");assertTrue(executable.canExecute())
        val runner=AndroidQnnProcessRunner();val env=mapOf("LD_LIBRARY_PATH" to native.absolutePath,"ADSP_LIBRARY_PATH" to native.absolutePath)
        val help=runner.run(listOf(executable.absolutePath,"--help"),env);assertTrue(help.output.contains("sense-voice"))
        val dir=File(context.cacheDir,"qnn-native-probe-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val model=File(dir,"model.bin").apply{writeText("invalid-test-context")};val tokens=File(dir,"tokens.txt").apply{writeText("<blk> 0\na 1\n")};val audio=File(dir,"public-test.wav");PcmWav.writeMono(audio,FloatArray(1600){.01f})
            val result=runner.run(listOf(executable.absolutePath,"--provider=qnn","--debug=true","--tokens=${tokens.path}","--sense-voice.qnn-context-binary=${model.path}","--sense-voice.qnn-backend-lib=${File(native,"missing-test-backend.so").path}","--sense-voice.qnn-system-lib=${File(native,"libQnnSystem.so").path}"),env)
            assertTrue(result.exit!=0);assertFalse(result.output.contains("graphExecute succeeded"));assertTrue(android.os.Process.myPid()>0)
        }finally{dir.deleteRecursively()}
    }
    @Test fun subprocessCancellationReturnsWithoutWaitingForSleep()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val connection=AndroidQnnWorkerConnection(listOf("/system/bin/sleep","20"),emptyMap())
        val job=launch(Dispatchers.Default){started.complete(Unit);try{connection.receive()}finally{connection.close()}}
        started.await();delay(150);withTimeout(3000){job.cancelAndJoin()};assertTrue(job.isCancelled);assertFalse(connection.alive)
    }
}
