package dev.soundsoftware.core.asr

import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAsrAdapterTest {
    @get:Rule val folder = TemporaryFolder()

    private fun request(): AsrRequest {
        val file = File(folder.root, "input.wav")
        PcmWav.writeMono(file, FloatArray(40000) { 0.1f })
        return AsrRequest("job", "attempt", AudioInput.fromWav(file), "profile-1")
    }

    @Test fun checkpointRecoverySkipsCompletedChunksAndKeepsTime() = runBlocking {
        val request = request()
        val store = FileCheckpointStore(File(folder.root, "checkpoints"))
        val failing = FakeRuntime(failAt = 2)
        try {
            LocalAsrAdapter(failing).transcribe(request, AsrExecutionContext(store))
            fail("Should fail at second chunk")
        } catch (e: AsrException) { assertEquals(ErrorCode.BACKEND_UNAVAILABLE, e.code) }
        val resumed = FakeRuntime()
        val result = LocalAsrAdapter(resumed).transcribe(request, AsrExecutionContext(store))
        assertEquals(2, resumed.calls)
        assertEquals(listOf(0L, 1000L, 2000L), result.segments.map { it.startMs })
        assertEquals(2500L, result.segments.last().endMs)
        assertEquals(TimestampQuality.APPROXIMATE_SEGMENT, result.timestampQuality)
    }

    @Test fun changedProfileDoesNotReusePreviousText() = runBlocking {
        val request = request()
        val store = MemoryCheckpointStore()
        LocalAsrAdapter(FakeRuntime()).transcribe(request, AsrExecutionContext(store))
        val second = FakeRuntime()
        LocalAsrAdapter(second).transcribe(request.copy(profileRevision = "profile-2"), AsrExecutionContext(store))
        assertEquals(3, second.calls)
    }

    @Test fun requiredPreciseTimestampFailsBeforeModelLoad() = runBlocking {
        val runtime = FakeRuntime()
        try {
            LocalAsrAdapter(runtime).transcribe(request().copy(options = AsrOptions(timestamps = TimestampRequirement.REQUIRE_SEGMENT)))
            fail("Required timestamps must not be fabricated")
        } catch (e: AsrException) { assertEquals(ErrorCode.UNSUPPORTED_CAPABILITY, e.code) }
        assertEquals(0, runtime.prepares)
    }

    @Test fun rejectsMutatedInputBeforeInference() = runBlocking {
        val request = request()
        request.audio.file.appendBytes(byteArrayOf(1))
        try { LocalAsrAdapter(FakeRuntime()).transcribe(request); fail("hash mismatch") }
        catch (e: AsrException) { assertEquals(ErrorCode.INVALID_AUDIO, e.code) }
    }

    @Test fun cancellationKeepsOnlyCompletedSegmentsForRecovery() = runBlocking {
        val request = request()
        val store = MemoryCheckpointStore()
        val enteredSecond = CompletableDeferred<Unit>()
        val interrupted = object : LocalAsrRuntime {
            override val modelId = "test-model"
            override val runtimeName = "fixture"
            override val backend = "fixture"
            override val maxSamples = 16000
            var calls = 0
            override suspend fun prepare() {}
            override suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition {
                if (++calls == 2) { enteredSecond.complete(Unit); awaitCancellation() }
                return LocalRecognition("completed")
            }
            override fun close() {}
        }
        val job = launch { LocalAsrAdapter(interrupted).transcribe(request, AsrExecutionContext(store)) }
        enteredSecond.await()
        job.cancelAndJoin()
        val resumed = FakeRuntime()
        val result = LocalAsrAdapter(resumed).transcribe(request, AsrExecutionContext(store))
        assertEquals(2, resumed.calls)
        assertEquals("completed", result.segments.first().text)
    }

    private class FakeRuntime(private val failAt: Int = -1) : LocalAsrRuntime {
        override val modelId = "test-model"
        override val runtimeName = "fixture"
        override val backend = "fixture"
        override val maxSamples = 16000
        var calls = 0; var prepares = 0
        override suspend fun prepare() { prepares++ }
        override suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition {
            calls++
            if (calls == failAt) throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "fixture failure")
            return LocalRecognition("测试 English")
        }
        override fun close() {}
    }
}
