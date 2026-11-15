package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.agent.AgentController
import dev.soundsoftware.app.agent.AgentSandbox
import dev.soundsoftware.app.agent.CodexModelRelay
import dev.soundsoftware.app.data.AssistantBackend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSandboxTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SoundApplication

    @Test fun codexRelaySendsTheConfiguredKeyOnlyUpstream() {
        val syntheticKey = "fixture-key"
        val sse = "event: fixture\ndata: demo\n\n"
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            CodexModelRelay(upstream.url("/custom/v1").newBuilder().host("localhost").build(), syntheticKey).use { relay ->
                val client = OkHttpClient()
                val request = Request.Builder().url(relay.localBase.replace("127.0.0.1", "localhost") + "/responses")
                    .post("""{"model":"fixture"}""".toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer untrusted-local-value").build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals(sse, response.body?.string())
                }
                val received = checkNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("/custom/v1/responses", received.path)
                assertEquals("Bearer $syntheticKey", received.getHeader("Authorization"))
                assertEquals("""{"model":"fixture"}""", received.body.readUtf8())
                client.newCall(request.newBuilder().url(relay.localBase.replace("127.0.0.1", "localhost") + "/models").build()).execute().use {
                    assertEquals(404, it.code)
                }
                assertEquals(1, upstream.requestCount)
            }
        }
    }

    @Test fun globalSessionKeepsItsIdAndAnUnsupportedBackendFailsClosed() = runBlocking {
        assumeTrue(app.packageName.endsWith(".agentpoc"))
        val sandbox = AgentSandbox(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val previous = app.settings.state.value.assistantBackend
        try {
            val current = withTimeout(5_000) { app.assistant.state.first { it.ready } }
            val newController = AgentController(app.recordingIndex, app.settings, scope, sandbox)
            newController.load()
            assertEquals(current.session.id, newController.state.value.session.id)
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
            if (!sandbox.supported) {
                app.settings.update { it.copy(assistantBackend = AssistantBackend.CODEX) }
                newController.ask("Synthetic question")
                val result = withTimeout(5_000) { newController.state.first { !it.busy && it.error != null } }
                assertEquals(current.session.id, result.session.id)
                assertEquals(current.session.turns, result.session.turns)
                assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agent-") })
            }
        } finally {
            app.settings.update { it.copy(assistantBackend = previous) }
            scope.cancel()
        }
    }
}
