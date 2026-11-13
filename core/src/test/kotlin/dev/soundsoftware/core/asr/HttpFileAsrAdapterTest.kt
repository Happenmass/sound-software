package dev.soundsoftware.core.asr

import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpFileAsrAdapterTest {
    @get:Rule val folder = TemporaryFolder()
    private fun request(): AsrRequest {
        val file = File(folder.root, "input.wav")
        PcmWav.writeMono(file, FloatArray(16000) { 0.1f })
        return AsrRequest("job", "attempt", AudioInput.fromWav(file), "revision")
    }
    private fun config(server: MockWebServer, timestamps: Boolean = false) = HttpAsrConfig(
        server.url("/v1/audio/transcriptions"), "fixture-model", supportsSegmentTimestamps = timestamps,
        allowInsecureLoopbackForTests = true,
    )

    @Test fun sendsMultipartAndReturnsUntimedTextWithoutInventingLocation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"你好 hello"}"""))
            val result = HttpFileAsrAdapter(config(server), { "fixture-key" }).transcribe(request())
            assertEquals("你好 hello", result.text)
            assertEquals(TimestampQuality.NONE, result.timestampQuality)
            assertNull(result.segments.single().startMs)
            val sent = server.takeRequest()
            assertEquals("Bearer fixture-key", sent.getHeader("Authorization"))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("name=\"file\""))
            assertFalse(body.contains("name=\"prompt\""))
        }
    }

    @Test fun acceptsValidSegmentTimesAndRecoversWithoutResubmission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"hello","segments":[{"text":"hello","start":0.1,"end":0.8}]}"""))
            val request = request()
            val adapter = HttpFileAsrAdapter(config(server, true), { null })
            val context = AsrExecutionContext(MemoryCheckpointStore())
            val first = adapter.transcribe(request, context)
            val second = adapter.transcribe(request, context)
            assertEquals(100L, first.segments.single().startMs)
            assertEquals(first.text, second.text)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsOutOfBoundsTimestampInsteadOfClamping() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"bad","segments":[{"text":"bad","start":0,"end":2}]}"""))
            try { HttpFileAsrAdapter(config(server, true), { null }).transcribe(request()); fail("invalid time") }
            catch (e: AsrException) { assertEquals(ErrorCode.INVALID_RESPONSE, e.code) }
        }
    }

    @Test fun disconnectedSubmissionIsNotAutomaticallyRepeated() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val adapter = HttpFileAsrAdapter(config(server), { null })
            val request = request(); val context = AsrExecutionContext(MemoryCheckpointStore())
            repeat(2) {
                try { adapter.transcribe(request, context); fail("state should be unknown") }
                catch (e: AsrException) { assertEquals(ErrorCode.REMOTE_STATE_UNKNOWN, e.code) }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unsupportedCapabilityIsRejectedBeforeUpload() = runBlocking {
        MockWebServer().use { server ->
            try {
                HttpFileAsrAdapter(config(server), { null }).transcribe(request().copy(options = AsrOptions(hotwords = listOf("test"))))
                fail("unsupported prompt")
            } catch (e: AsrException) { assertEquals(ErrorCode.UNSUPPORTED_CAPABILITY, e.code) }
            assertEquals(0, server.requestCount)
        }
    }
}
