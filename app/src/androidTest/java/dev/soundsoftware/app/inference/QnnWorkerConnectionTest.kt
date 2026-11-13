package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QnnWorkerConnectionTest {
    private val device=QnnDevice("SM8850",true,false,4096)
    @Test fun sm8850ModelCompatibilityUsesDetectedHardware() {
        assertTrue(device.supportsSm8850Htp)
        assertTrue(device.copy(soc = "sm8850").supportsSm8850Htp)
        assertFalse(device.copy(soc = "SM8650").supportsSm8850Htp)
        assertFalse(device.copy(arm64 = false).supportsSm8850Htp)
        assertFalse(device.copy(emulator = true).supportsSm8850Htp)
        assertFalse(device.copy(pageSize = 16384).supportsSm8850Htp)
    }

    @Test fun subprocessCancellationReturnsWithoutWaitingForSleep()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val connection=AndroidQnnWorkerConnection(listOf("/system/bin/sleep","20"),emptyMap())
        val job=launch(Dispatchers.Default){started.complete(Unit);try{connection.receive()}finally{connection.close()}}
        started.await();delay(150);withTimeout(3000){job.cancelAndJoin()};assertTrue(job.isCancelled);assertFalse(connection.alive)
    }
}
