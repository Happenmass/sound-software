package dev.soundsoftware.core.asr

import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.transcript.SpeakerTurn
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

    private fun request(durationMs: Int = 2500): AsrRequest {
        val file = File(folder.root, "input.wav")
        PcmWav.writeMono(file, FloatArray(durationMs * 16) { 0.1f })
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

    @Test fun mergesAudioBeforeDecodeAndKeepsOneSegmentAcrossModelWindows() = runBlocking {
        val runtime = FakeRuntime()
        val adapter = LocalAsrAdapter(runtime, speakerTurns = listOf(
            SpeakerTurn(0, 350, 4), SpeakerTurn(450, 900, 4), SpeakerTurn(950, 1450, 4),
            SpeakerTurn(1500, 1600, 9), SpeakerTurn(1800, 2000, 4), SpeakerTurn(2100, 2500, -1),
        ))
        val result = adapter.transcribe(request())
        // The first two fragments go into the same model call. The next fragment exceeds
        // this runtime's 1s limit and starts at its original boundary instead of 1000ms.
        assertEquals(listOf(14_400, 8_000, 1_600, 3_200, 6_400), runtime.windowSizes)
        assertEquals(listOf(
            TranscriptSegment("part-0", "测试 English 测试 English", 0, 1450, "spk-4", listOf("测试 English", "测试 English")),
            TranscriptSegment("part-1", "测试 English", 1500, 1600, "spk-9"),
            TranscriptSegment("part-2", "测试 English", 1800, 2000, "spk-4"),
            TranscriptSegment("part-3", "测试 English", 2100, 2500),
        ), result.segments)
    }

    @Test fun packsContinuousSpeakerTurnsAtSpeechBoundariesWithin24Seconds() = runBlocking {
        val runtime = FakeRuntime(maxSamples = 24 * 16_000)
        val result = LocalAsrAdapter(runtime, speakerTurns = listOf(
            SpeakerTurn(0, 10_000, 4),
            SpeakerTurn(11_000, 20_000, 4),
            SpeakerTurn(20_500, 28_000, 4),
            SpeakerTurn(28_500, 44_500, 4),
            SpeakerTurn(46_000, 46_300, 4),
            SpeakerTurn(46_500, 47_000, 4),
            SpeakerTurn(47_000, 48_000, 9),
        )).transcribe(request(48_000))

        // Short pauses count toward the model limit. A 1.5s pause or speaker change ends the group.
        assertEquals(listOf(20, 24, 1, 1).map { it * 16_000 }, runtime.windowSizes)
        assertEquals(listOf(0L, 46_000L, 47_000L), result.segments.map { it.startMs })
        assertEquals(listOf(44_500L, 47_000L, 48_000L), result.segments.map { it.endMs })
        assertEquals(listOf("spk-4", "spk-4", "spk-9"), result.segments.map { it.speaker })
        assertEquals(listOf(2, 1, 0), result.segments.map { it.fragments.size })
    }

    @Test fun splitsAnOverlongTurnAndPacksItsTailWithTheNextTurn() = runBlocking {
        val runtime = FakeRuntime(maxSamples = 24 * 16_000)
        val result = LocalAsrAdapter(runtime, speakerTurns = listOf(
            SpeakerTurn(0, 7_000, 1),
            SpeakerTurn(7_500, 34_000, 1),
            SpeakerTurn(34_500, 36_500, 1),
        )).transcribe(request(36_500))

        assertEquals(listOf(7, 24, 5).map { it * 16_000 }, runtime.windowSizes)
        assertEquals(36_500L, result.segments.single().endMs)
        assertEquals(3, result.segments.single().fragments.size)
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

    private class FakeRuntime(
        private val failAt: Int = -1,
        override val maxSamples: Int = 16000,
    ) : LocalAsrRuntime {
        override val modelId = "test-model"
        override val runtimeName = "fixture"
        override val backend = "fixture"
        var calls = 0; var prepares = 0
        val windowSizes = mutableListOf<Int>()
        override suspend fun prepare() { prepares++ }
        override suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition {
            windowSizes += samples.size
            calls++
            if (calls == failAt) throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "fixture failure")
            return LocalRecognition("测试 English")
        }
        override fun close() {}
    }
}
