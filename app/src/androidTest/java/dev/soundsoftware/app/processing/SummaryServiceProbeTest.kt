package dev.soundsoftware.app.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.analysis.*
import dev.soundsoftware.core.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import dev.soundsoftware.app.SoundApplication
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit user-selected LLM probe. Exports metadata only. Untrusted content drafts remain in
 * the app's private test cache for diagnosis/replay; never cache keys or model reasoning. */
@RunWith(AndroidJUnit4::class)
class SummaryServiceProbeTest {
    @Test fun regenerateFailedSummariesThroughProductionProcessor() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("summaryProbe") == "retry")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val json = Json { ignoreUnknownKeys = true }
        val targets = File(context.filesDir, "audio").listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .map { json.decodeFromString<Recording>(it.readText()) }
            .filter { it.durationMs > 60_000 && it.summary == null && it.error?.startsWith("纪要未生成") == true }.sortedBy { it.createdAt }
        assertTrue(targets.isNotEmpty())
        withTimeout(10_000) { app.repository.recordings.first { all -> targets.all { target -> all.any { it.id == target.id } } } }
        val report = JSONObject().put("status", "running").put("model", app.settings.state.value.llm.model)
            .put("requestedReasoningEffort", "medium").put("appOutputTokenLimit", JSONObject.NULL).put("appContextLimit", JSONObject.NULL)
        val evidence = File(context.getExternalFilesDir(null), "validation/summary-recovery.json").apply { parentFile!!.mkdirs() }
        val beforeHashes = targets.associate { it.id to MeetingAnalyzer.transcriptHash(it) }
        val callDiagnostics = JSONArray().also { report.put("calls", it) }
        val drafts = File(context.cacheDir, "summary-probe-drafts").apply { mkdirs() }
        val replay = InstrumentationRegistry.getArguments().getString("summaryReplay") == "true"
        fun save() {
            report.put("recordings", JSONArray(targets.mapIndexed { i, base ->
                val r = app.repository.get(base.id) ?: base
                JSONObject().put("item", i + 1).put("durationMs", r.durationMs).put("stage", r.stage.name)
                    .put("stageText", r.stageText).put("error", r.error)
                    .put("transcriptPreserved", MeetingAnalyzer.transcriptHash(r) == beforeHashes[base.id])
                    .put("summaryPresent", r.summary != null).put("abstractChars", r.summary?.abstract?.length ?: 0)
                    .put("keyPoints", r.summary?.keyPoints?.size ?: 0).put("todos", r.summary?.todos?.size ?: 0)
                    .put("chapters", r.summary?.chapters?.size ?: 0)
                    .put("summaryMatchesTranscript", r.summary?.sourceTranscriptHash == beforeHashes[base.id])
            }))
            evidence.writeText(report.toString(2))
        }
        save()
        val watch = launch { app.repository.recordings.collect { save() } }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val input = json.parseToJsonElement(body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
            val source = input["segments"]!!.jsonArray.map { it.jsonObject }
            val target = targets.single { it.transcript!!.segments.count { s -> s.text.isNotBlank() } == source.size }
            val draft = File(drafts, "${beforeHashes.getValue(target.id)}.json")
            val response = if (replay && draft.isFile) {
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Private draft replay")
                    .body(draft.readText().toResponseBody("application/json".toMediaType())).build()
            } else chain.proceed(chain.request())
            val raw = response.peekBody(Long.MAX_VALUE).string()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val choice = root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            val call = JSONObject().put("item", targets.indexOf(target) + 1).put("httpStatus", response.code).put("finishReason", finish)
                .put("replayed", replay && draft.isFile).put("contentChars", content.length)
                .put("hasOutputCap", body.containsKey("max_tokens") || body.containsKey("max_completion_tokens"))
                .put("reasoningEffort", body["reasoning_effort"]?.jsonPrimitive?.content)
            root?.get("usage")?.jsonObject?.let { usage ->
                for (field in listOf("prompt_tokens", "completion_tokens", "total_tokens"))
                    usage[field]?.jsonPrimitive?.longOrNull?.let { call.put(field, it) }
            }
            if (response.isSuccessful && finish == "stop") {
                // Untrusted draft stays only in the app's private test cache; omit thinking and keys.
                val clean = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("finish_reason", "stop"); put("message", buildJsonObject { put("content", content) })
                    }) })
                }
                draft.writeText(clean.toString())
                val normalized = { s: String -> s.filter { it.isLetterOrDigit() } }
                val sourceById = source.associate { it["segmentId"]!!.jsonPrimitive.content to it["text"]!!.jsonPrimitive.content }
                var refs = 0; var missing = 0; var exact = 0; var sourceOnly = 0; var normalizedMatch = 0; var otherExact = 0
                fun visit(value: JsonElement) {
                    when (value) {
                        is JsonObject -> if (value.containsKey("segmentId")) {
                            refs++
                            val id = value["segmentId"]!!.jsonPrimitive.content
                            val quote = value["quote"]?.jsonPrimitive?.content
                            val text = sourceById[id]
                            if (text == null) missing++
                            else if (quote == null) sourceOnly++
                            else if (text.contains(quote)) exact++
                            else if (normalized(quote).isNotEmpty() && normalized(text).contains(normalized(quote))) normalizedMatch++
                            if (quote != null && sourceById.values.count { it.contains(quote) } == 1 && (text == null || !text.contains(quote))) otherExact++
                        } else value.values.forEach(::visit)
                        is JsonArray -> value.forEach(::visit)
                        else -> Unit
                    }
                }
                val trimmed = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                runCatching { visit(json.parseToJsonElement(trimmed)) }
                call.put("references", refs).put("missingSegmentId", missing).put("exactQuote", exact).put("segmentIdOnly", sourceOnly)
                    .put("punctuationOrWhitespaceOnly", normalizedMatch).put("exactQuoteInUniqueOtherSegment", otherExact)
            }
            callDiagnostics.put(call); save()
            response
        }.build()
        val worker = Processor(app.repository, app.settings, app.modelDir, File(context.filesDir, "checkpoints"), app.scope,
            analysisClient = client, notify = {})
        try {
            val jobs = targets.map { r -> worker.resummarize(r.id, r.options?.template?.takeUnless { it == SceneTemplate.TRANSCRIPT_ONLY } ?: SceneTemplate.MEETING) }
            jobs.joinAll()
            save()
            for (target in targets) {
                val r = requireNotNull(app.repository.get(target.id))
                assertNull(r.error)
                assertNotNull(r.summary)
                assertEquals(beforeHashes[target.id], MeetingAnalyzer.transcriptHash(r))
                assertEquals(beforeHashes[target.id], r.summary!!.sourceTranscriptHash)
            }
            report.put("status", "passed"); save()
        } finally { watch.cancelAndJoin(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun inspectFailedSummaryResponseMetadata() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("summaryProbe") == "baseline")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val llm = settings.state.value.llm
        val key = settings.secrets.get(Processor.KEY_LLM)
        check(!key.isNullOrBlank()) { "Configured LLM key is required" }
        val json = Json { ignoreUnknownKeys = true }
        val targets = File(context.filesDir, "audio").listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .map { json.decodeFromString<Recording>(it.readText()) }
            .filter { it.error?.contains("纪要服务输出未完成") == true }.sortedBy { it.createdAt }
        assertEquals(2, targets.size)
        val report = JSONObject().put("status", "running").put("model", llm.model)
        val records = JSONArray().also { report.put("recordings", it) }
        val evidence = File(context.getExternalFilesDir(null), "validation/summary-baseline.json").apply { parentFile!!.mkdirs() }
        fun save() { evidence.writeText(report.toString(2)) }
        save()
        for ((index, recording) in targets.withIndex()) {
            val calls = JSONArray()
            val entry = JSONObject().put("item", index + 1).put("durationMs", recording.durationMs)
                .put("transcriptChars", recording.transcript!!.segments.sumOf { it.text.length }).put("calls", calls)
            records.put(entry); save()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val raw = response.peekBody(Long.MAX_VALUE).string()
                val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val choice = root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                val message = choice?.get("message")?.jsonObject
                val usage = root?.get("usage")?.jsonObject
                val call = JSONObject().put("httpStatus", response.code)
                    .put("finishReason", choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull)
                    .put("contentChars", message?.get("content")?.jsonPrimitive?.contentOrNull?.length ?: 0)
                    .put("reasoningChars", message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.length ?: 0)
                for (field in listOf("prompt_tokens", "completion_tokens", "total_tokens", "reasoning_tokens"))
                    usage?.get(field)?.jsonPrimitive?.longOrNull?.let { call.put(field, it) }
                usage?.get("completion_tokens_details")?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull?.let { call.put("reasoning_tokens", it) }
                calls.put(call); save()
                response
            }.build()
            try {
                val cached = FileAnalysisCheckpoints(File(context.filesDir, "checkpoints/analysis/${java.util.UUID.nameUUIDFromBytes(recording.id.toByteArray())}"))
                val readOnly = object : AnalysisCheckpoints {
                    override suspend fun read(key: String) = cached.read(key)
                    override suspend fun write(key: String, content: String) {}
                }
                val analyzer = MeetingAnalyzer(LlmConfig(llm.baseUrl.trimEnd('/').plus("/").toHttpUrl(), llm.model), { key }, client, readOnly)
                analyzer.summarize(recording, recording.options?.template?.takeUnless { it == SceneTemplate.TRANSCRIPT_ONLY } ?: SceneTemplate.MEETING,
                    recording.options?.hotwords.orEmpty()) { entry.put("stage", it); save() }
                entry.put("result", "completed")
            } catch (e: AnalysisException) {
                entry.put("result", "reproduced-failure").put("error", e.message)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); save() }
        }
        report.put("status", "complete"); save()
        assertTrue((0 until records.length()).all { i ->
            val calls = records.getJSONObject(i).getJSONArray("calls")
            (0 until calls.length()).any { calls.getJSONObject(it).optString("finishReason") == "length" }
        })
    }
}
