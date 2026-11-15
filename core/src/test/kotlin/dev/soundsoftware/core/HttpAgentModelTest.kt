package dev.soundsoftware.core

import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.LlmConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HttpAgentModelTest {
    private val json = Json
    @Test fun httpAdapterSendsNativeToolsAndHandlesToolCallReply()=runTest {
        val query = "release".repeat(400)
        MockWebServer().use {server->server.start()
            val arguments = json.encodeToString(buildJsonObject { put("query", query) })
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"search_recordings","arguments":${json.encodeToString(arguments)}}}]}}]}"""))
            val model=HttpAgentModel(LlmConfig(server.url("v1/"),"synthetic",true),"test-only")
            val reply=model.complete(listOf(AgentMessage("user","question")),true)
            assertEquals("search_recordings",reply.calls.single().name)
            assertEquals(query, reply.calls.single().arguments.getValue("query").jsonPrimitive.content)
            val request=server.takeRequest();val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(5,body.getValue("tools").jsonArray.size);assertEquals("Bearer test-only",request.getHeader("Authorization"))
            assertFalse(body.containsKey("max_tokens"));assertFalse(body.containsKey("max_completion_tokens"))
            assertEquals("medium",body.getValue("reasoning_effort").jsonPrimitive.content)
        }
    }
    @Test fun httpUsageReadsProviderCountsWithoutAddingCachedOrReasoningTokensAgain()=runTest {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}],"usage":{"prompt_tokens":199900,"completion_tokens":100,"total_tokens":200000,"prompt_tokens_details":{"cached_tokens":100000},"completion_tokens_details":{"reasoning_tokens":80}}}"""))
            val out=HttpAgentModel(LlmConfig(server.url("/"),"synthetic",true),null).complete(listOf(AgentMessage("user","q")),true)
            assertEquals(AgentUsage(199900,100,200000),out.usage)
        }
    }
    @Test fun httpReadsLargeCompleteResponsesAndKeepsCancellationAttachedAfterHeaders()=runBlocking {
        MockWebServer().use {server->
            val response=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {
                put("finish_reason","stop");put("message",buildJsonObject {put("content","{}");put("reasoning_content","r".repeat(300000))})
            })})}.toString()
            server.enqueue(MockResponse().setBody(response))
            val config=LlmConfig(server.url("/"),"synthetic",true)
            val full=HttpAgentModel(config,null).complete(listOf(AgentMessage("user","q")),false)
            assertEquals("{}",full.text);assertNull(full.usage)
            server.enqueue(MockResponse().setBody(response).setBodyDelay(3,TimeUnit.SECONDS))
            val reading=CompletableDeferred<Unit>()
            val client=OkHttpClient.Builder().addNetworkInterceptor {chain->
                val result=chain.proceed(chain.request());val original=result.body!!
                val source=object:ForwardingSource(original.source()) {
                    override fun read(sink:Buffer,byteCount:Long):Long {reading.complete(Unit);return super.read(sink,byteCount)}
                }.buffer()
                result.newBuilder().body(object:ResponseBody() {
                    override fun contentType()=original.contentType()
                    override fun contentLength()=original.contentLength()
                    override fun source()=source
                }).build()
            }.build()
            try {
                val job=launch(Dispatchers.IO) {HttpAgentModel(config,null,client).complete(listOf(AgentMessage("user","q")),true)}
                withTimeout(3000){reading.await()};withTimeout(2000){job.cancelAndJoin()};assertTrue(job.isCancelled)
            } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
        }
    }
    @Test fun httpAdapterRejectsTruncatedOutputAndDoesNotFollowRedirects()=runTest {
        MockWebServer().use {server->server.start();val model=HttpAgentModel(LlmConfig(server.url("v1/"),"synthetic",true),null)
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}"""))
            assertTrue(runCatching{model.complete(listOf(AgentMessage("user","q")),true)}.isFailure)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("other")))
            assertTrue(runCatching{model.complete(listOf(AgentMessage("user","q")),true)}.isFailure);assertEquals(2,server.requestCount)
        }
    }
}
