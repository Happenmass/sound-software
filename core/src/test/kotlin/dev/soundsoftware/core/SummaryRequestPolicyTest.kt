package dev.soundsoftware.core

import dev.soundsoftware.core.analysis.*
import dev.soundsoftware.core.model.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class SummaryRequestPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun recording(text: String = "交互稿需周三提交。") = Recording("synthetic", 0, 1000,
        transcript = Transcript(listOf(Segment("source", text, 0, 1000)), "fake", "test", "SEGMENT", 0))
    private fun notes(abstract: String = "需提交交互稿。") = buildJsonObject {
        put("title", "合成纪要"); put("abstract", abstract)
        put("abstractEvidence", buildJsonArray { add(buildJsonObject { put("segmentId", "source"); put("quote", "交互稿") }) })
        listOf("keyPoints", "decisions", "risks", "openQuestions", "todos", "chapters").forEach { put(it, buildJsonArray {}) }
    }
    private fun reply(content: String, finish: String = "stop", reasoning: String = "") = MockResponse().setBody(buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish); put("message", buildJsonObject { put("content", content); put("reasoning_content", reasoning) })
        }) })
    }.toString())

    @Test fun reasoningCannotExhaustAnAppImposed4000TokenOutputCap() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    if (body.containsKey("max_tokens") || body.containsKey("max_completion_tokens")) return reply("", "length", "思考过程")
                    assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
                    return reply(notes().toString())
                }
            }
            val result = MeetingAnalyzer(LlmConfig(server.url("/"), "synthetic", true), { null })
                .summarize(recording(), SceneTemplate.MEETING, emptyList())
            assertEquals("需提交交互稿。", result.abstract)
        }
    }

    @Test fun defaultSummarySendsFullTranscriptAndAcceptsCompleteLargeResponseAndCheckpoint() = runBlocking {
        val dir = Files.createTempDirectory("summary-policy-").toFile()
        try {
            MockWebServer().use { server ->
                val text = "交互稿" + "完整输入内容".repeat(20_000)
                val abstract = "完整纪要内容".repeat(12_000)
                server.enqueue(reply(notes(abstract).toString(), reasoning = "思考".repeat(160_000)))
                val result = MeetingAnalyzer(LlmConfig(server.url("/"), "synthetic", true), { null }, checkpoints = FileAnalysisCheckpoints(dir))
                    .summarize(recording(text), SceneTemplate.MEETING, emptyList())
                assertEquals(abstract, result.abstract)
                assertEquals(1, server.requestCount)
                val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val input = json.parseToJsonElement(request["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                assertEquals(text, input["segments"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
                assertTrue(dir.listFiles().orEmpty().single().length() > 64_000)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationAfterResponseHeadersStillCancelsBodyReadWithoutSaving() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply(notes().toString()).setBodyDelay(3, TimeUnit.SECONDS))
            val readingBody = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = requireNotNull(response.body)
                val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readingBody.complete(Unit)
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = source
                }).build()
            }.build()
            try {
                val engine = MeetingAnalyzer(LlmConfig(server.url("/"), "synthetic", true), { null }, client)
                val job = launch(Dispatchers.IO) { engine.summarize(recording(), SceneTemplate.MEETING, emptyList()) }
                withTimeout(3000) { readingBody.await() }
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
