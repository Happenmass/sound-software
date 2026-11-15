package dev.soundsoftware.core.analysis

import dev.soundsoftware.core.model.*
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** User-selected text endpoint. Production defaults leave context/output capacity to the service. */
data class LlmConfig(
    val baseUrl: HttpUrl,
    val model: String,
    val allowInsecureForTests: Boolean = false,
    val chunkChars: Int? = null,
    val mergeChars: Int? = null,
    val reasoningEffort: String = "medium",
    val outputLanguage: String = "zh",
) {
    init {
        require(model.isNotBlank())
        require(baseUrl.isHttps || allowInsecureForTests) { "纪要服务地址必须使用 HTTPS" }
        require(chunkChars == null || chunkChars >= 128)
        require(mergeChars == null || mergeChars >= 1024)
        require(reasoningEffort.isNotBlank())
    }
    val chatUrl: HttpUrl get() = baseUrl.newBuilder().addPathSegments("chat/completions").build()
}

class AnalysisException(message: String, val retryable: Boolean = false) : Exception(message)

data class GroundedAnswer(val text: String, val evidence: List<EvidenceRef>, val sourceTranscriptHash: String)

/** Full transcript by default; explicit offline/test chunking remains available. No audio or external tools. */
class MeetingAnalyzer(
    private val config: LlmConfig,
    private val apiKey: () -> String?,
    client: OkHttpClient = OkHttpClient(),
    private val checkpoints: AnalysisCheckpoints = NoAnalysisCheckpoints,
) {
    private val client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun message(chinese: String, english: String) = if (config.outputLanguage == "en") english else chinese

    private data class Part(val segment: Segment, val text: String, val speaker: String?) {
        fun data() = buildJsonObject {
            put("segmentId", segment.id); put("text", text); put("speaker", speaker)
            put("startMs", segment.startMs); put("endMs", segment.endMs)
        }
    }
    private data class Fact(val text: String, val refs: List<EvidenceRef>)
    private data class Notes(val raw: JsonObject, val title: String?, val abstract: String, val abstractRefs: List<EvidenceRef>,
        val facts: Map<String, List<Fact>>, val todos: List<TodoItem>, val chapters: List<Chapter>)
    private data class Answer(val raw: JsonObject, val found: Boolean, val text: String, val refs: List<EvidenceRef>)

    suspend fun repairTranscript(fragments: List<String>): String {
        require(fragments.size > 1 && fragments.none { it.isBlank() })
        val input = buildJsonObject { put("fragments", buildJsonArray { fragments.forEach { add(it) } }) }
        val result = parseObject(chat(MeetingPrompts.REPAIR_TRANSCRIPT, input, localizeOutput = false))
        result.only("text")
        return result.string("text").trim()
    }

    suspend fun summarize(recording: Recording, template: SceneTemplate, hotwords: List<String>, onProgress: (String) -> Unit = {}): MeetingSummary {
        require(template != SceneTemplate.TRANSCRIPT_ONLY)
        val source = parts(recording)
        val resolvedTemplate = if (template == SceneTemplate.AUTO) classify(recording, source, onProgress) else template
        val chunks = chunk(source)
        val fingerprint = fingerprint(recording, "summary", resolvedTemplate.name, buildJsonArray { hotwords.forEach { add(it) } }.toString())
        var notes = chunks.mapIndexed { index, block ->
            onProgress(message("整理逐字稿 ${index + 1}/${chunks.size}", "Reading transcript ${index + 1}/${chunks.size}"))
            val data = buildJsonObject {
                put("segments", buildJsonArray { block.forEach { add(it.data()) } })
                put("hotwords", buildJsonArray { hotwords.forEach { add(it) } })
                put("hardwareHighlights", highlightData(recording,block))
                put("hardwareHighlightsStatus",recording.hardwareHighlightsStatus)
            }
            notesStage("$fingerprint/map/$index", resolvedTemplate, false, data, block, recording)
        }
        var level = 0
        while (notes.size > 1) {
            onProgress(message("汇总纪要", "Combining notes"))
            val groups = groups(notes) { it.raw.toString().length }
            notes = groups.mapIndexed { index, group ->
                if (group.size == 1) group.single() else {
                    val data = buildJsonObject { put("notes", buildJsonArray { group.forEach { add(it.raw) } });put("hardwareHighlights",highlightData(recording,source));put("hardwareHighlightsStatus",recording.hardwareHighlightsStatus) }
                    notesStage("$fingerprint/reduce/$level/$index/${digest(data.toString())}", resolvedTemplate, true, data, source, recording,
                        group.flatMap { allReferences(it.raw) }.toSet())
                }
            }
            level++
        }
        val result = notes.single()
        val evidence = linkedMapOf("abstract" to result.abstractRefs)
        result.facts.forEach { (category, facts) -> facts.forEachIndexed { index, fact -> evidence["$category/$index"] = fact.refs } }
        return MeetingSummary(result.title, result.abstract, result.todos.distinctBy { it.id },
            result.chapters.distinctBy { it.startMs to it.title }.sortedBy { it.startMs },
            result.facts.getValue("keyPoints").map { it.text }, resolvedTemplate, config.model, System.currentTimeMillis(),
            decisions = result.facts.getValue("decisions").map { it.text }, risks = result.facts.getValue("risks").map { it.text },
            openQuestions = result.facts.getValue("openQuestions").map { it.text }, evidence = evidence,
            promptVersion = MeetingPrompts.VERSION, sourceTranscriptHash = transcriptHash(recording))
    }

    private suspend fun classify(recording: Recording, source: List<Part>, onProgress: (String) -> Unit): SceneTemplate {
        onProgress(message("判断纪要类型", "Choosing a notes template"))
        val key = fingerprint(recording, "summary-scene", MeetingPrompts.CLASSIFY)
        val choices = listOf(SceneTemplate.MEETING, SceneTemplate.INTERVIEW, SceneTemplate.JOB_INTERVIEW, SceneTemplate.LECTURE)
        checkpoints.read(key)?.let { cached -> choices.firstOrNull { it.name == cached }?.let { return it } }
        val response = chat(MeetingPrompts.CLASSIFY, buildJsonObject {
            put("segments", buildJsonArray { source.forEach { add(it.data()) } })
        })
        val selected = runCatching {
            val result = parseObject(response)
            result.only("template")
            val name = result.string("template")
            choices.firstOrNull { it.name == name }
        }.getOrNull() ?: return SceneTemplate.MEETING
        checkpoints.write(key, selected.name)
        return selected
    }

    suspend fun answer(recording: Recording, question: String, history: List<QaTurn>): String = answerGrounded(recording, question, history).text

    suspend fun answerGrounded(recording: Recording, question: String, history: List<QaTurn>): GroundedAnswer {
        require(question.isNotBlank() && question.length <= 4000) { "问题需为1至4000字符" }
        val source = parts(recording)
        // Whole recent turns only. History clarifies references; it is not transcript evidence.
        val sourceHash = transcriptHash(recording)
        val recent = history.filter { it.sourceTranscriptHash == sourceHash }.takeLast(6).reversed().runningFold(emptyList<QaTurn>()) { acc, turn -> acc + turn }
            .last { turns -> turns.sumOf { it.question.length + it.answer.length } <= 6000 }.reversed()
        val context = buildJsonObject {
            put("question", question)
            put("history", buildJsonArray { recent.forEach { add(buildJsonObject { put("question", it.question); put("answer", it.answer) }) } })
        }
        val fingerprint = fingerprint(recording, "answer", context.toString())
        var answers = chunk(source).mapIndexed { index, block ->
            val data = buildJsonObject {
                put("segments", buildJsonArray { block.forEach { add(it.data()) } }); put("context", context)
                put("hardwareHighlights",highlightData(recording,block))
                put("hardwareHighlightsStatus",recording.hardwareHighlightsStatus)
            }
            answerStage("$fingerprint/map/$index", false, data, block)
        }.filter { it.found }
        var level = 0
        while (answers.size > 1) {
            answers = groups(answers) { it.raw.toString().length }.mapIndexed { index, group ->
                if (group.size == 1) group.single() else {
                    val data = buildJsonObject {
                        put("answers", buildJsonArray { group.forEach { add(it.raw) } }); put("context", context)
                    }
                    answerStage("$fingerprint/reduce/$level/$index/${digest(data.toString())}", true, data, source,
                        group.flatMap { it.refs }.toSet())
                }
            }.filter { it.found }
            level++
        }
        val result = answers.singleOrNull()
        val refs = result?.refs.orEmpty().distinct()
        val citationText = refs.map { ref ->
            val segment = source.first { it.segment.id == ref.segmentId }.segment
            val who = recording.speakerName(segment.speakerId)
            listOfNotNull(segment.startMs?.let(::clock), who).joinToString(" · ").ifBlank { "片段 ${ref.segmentId}" }
        }.distinct().joinToString("；")
        val text = result?.text?.let { "$it\n\n依据：$citationText" } ?: "逐字稿没有提到相关信息。"
        return GroundedAnswer(text, refs, transcriptHash(recording))
    }

    private fun parts(recording: Recording): List<Part> {
        val segments = recording.transcript?.segments.orEmpty().filter { it.text.isNotBlank() }
        if (segments.isEmpty()) throw AnalysisException(message("逐字稿为空，无法整理", "The transcript is empty."))
        if (segments.any { it.id.isBlank() } || segments.map { it.id }.distinct().size != segments.size)
            throw AnalysisException(message("逐字稿片段标识不唯一，无法可靠引用", "Transcript passage IDs are duplicated. Citations could not be verified."))
        return segments.map { Part(it, it.text.trim(), recording.speakerName(it.speakerId)) }
    }

    private fun highlightData(recording: Recording, scope: List<Part>) = buildJsonArray {
        val ids=scope.map {it.segment.id}.toSet()
        recording.hardwareHighlights.orEmpty().forEach {mark->
            val nearby=recording.highlightContext(mark).map {it.id}.filter {it in ids}
            add(buildJsonObject {
                put("id",mark.id);put("positionMs",mark.positionMs)
                put("nearbySegmentIds",buildJsonArray {nearby.forEach {add(it)}})
            })
        }
    }

    private fun chunk(source: List<Part>): List<List<Part>> {
        val budget = config.chunkChars ?: return listOf(source)
        val result = mutableListOf<List<Part>>()
        var current = mutableListOf<Part>(); var size = 0
        source.forEach { part ->
            var offset = 0
            while (offset < part.text.length) {
                val overhead = part.copy(text = "").data().toString().length + 1
                val room = budget - overhead - 2
                if (room < 8) throw AnalysisException("单条片段标识或说话人信息过长，无法分块")
                var end = minOf(part.text.length, offset + room)
                if (end < part.text.length && part.text[end - 1].isHighSurrogate() && part.text[end].isLowSurrogate()) end--
                var piece = part.copy(text = part.text.substring(offset, end))
                // JSON escaping can exceed the raw character length. Shrink without dropping text.
                while (piece.data().toString().length + 2 > budget) {
                    end = offset + maxOf(1, (end - offset) * 3 / 4)
                    if (end < part.text.length && part.text[end - 1].isHighSurrogate() && part.text[end].isLowSurrogate()) end--
                    if (end <= offset) throw AnalysisException("片段无法装入请求预算")
                    piece = part.copy(text = part.text.substring(offset, end))
                }
                val cost = piece.data().toString().length + 1
                if (size + cost + 2 > budget && current.isNotEmpty()) { result += current; current = mutableListOf(); size = 0 }
                current += piece; size += cost; offset = end
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun <T> groups(items: List<T>, length: (T) -> Int): List<List<T>> {
        val budget = config.mergeChars ?: return listOf(items)
        val result = mutableListOf<List<T>>(); var current = mutableListOf<T>(); var size = 0
        for (item in items) {
            val cost = length(item) + 1
            if (cost + 2 > budget) throw AnalysisException("分段整理结果过长，无法在预算内合并；已完成分段保留")
            if (size + cost + 2 > budget && current.isNotEmpty()) { result += current; current = mutableListOf(); size = 0 }
            current += item; size += cost
        }
        if (current.isNotEmpty()) result += current
        if (items.size > 1 && result.size >= items.size) throw AnalysisException("整理结果不能继续压缩到请求预算，未省略任何片段")
        return result
    }

    private suspend fun notesStage(key: String, template: SceneTemplate, merge: Boolean, input: JsonObject, scope: List<Part>, recording: Recording,
        allowed: Set<EvidenceRef>? = null): Notes {
        val hash = digest(key)
        checkpoints.read(hash)?.let { cached -> runCatching { parseNotes(parseObject(cached), scope, recording, allowed).also { validateResultBudget(it.raw) } }.getOrNull()?.let { return it } }
        val response = chat(MeetingPrompts.summary(template, merge), input)
        val notes = parseNotes(parseObject(response), scope, recording, allowed)
        validateResultBudget(notes.raw)
        checkpoints.write(hash, notes.raw.toString())
        return notes
    }

    private suspend fun answerStage(key: String, merge: Boolean, input: JsonObject, scope: List<Part>, allowed: Set<EvidenceRef>? = null): Answer {
        val hash = digest(key)
        checkpoints.read(hash)?.let { cached -> runCatching { parseAnswer(parseObject(cached), scope).also { validateMergeReferences(it.raw, allowed); validateResultBudget(it.raw) } }.getOrNull()?.let { return it } }
        val result = parseAnswer(parseObject(chat(MeetingPrompts.answer(merge), input)), scope)
        validateMergeReferences(result.raw, allowed)
        validateResultBudget(result.raw)
        checkpoints.write(hash, result.raw.toString())
        return result
    }

    private fun parseNotes(root: JsonObject, scope: List<Part>, recording: Recording, allowed: Set<EvidenceRef>?): Notes = checked {
        root.only("title", "abstract", "abstractEvidence", "keyPoints", "decisions", "risks", "openQuestions", "todos", "chapters")
        val byId = scope.groupBy { it.segment.id }
        fun verifiedRefs(array: JsonArray): List<EvidenceRef> = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["segmentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val parts = byId[id] ?: return@mapNotNull null
            val quote = when (obj.keys) {
                setOf("segmentId") -> parts.first().text
                setOf("segmentId", "quote") -> (obj["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                else -> null
            } ?: return@mapNotNull null
            if (quote.isBlank() || parts.none { it.text.contains(quote) }) return@mapNotNull null
            EvidenceRef(id, quote).takeIf { allowed == null || it in allowed }
        }.distinct()
        val abstractRefs = verifiedRefs(root.array("abstractEvidence"))
        val abstract = root.string("abstract", blank = true).takeIf { abstractRefs.isNotEmpty() }.orEmpty()
        val facts = listOf("keyPoints", "decisions", "risks", "openQuestions").associateWith { category ->
            root.array(category).mapNotNull { element ->
                val obj = element.jsonObject
                obj.only("text", "evidence")
                val refs = verifiedRefs(obj.array("evidence"))
                if (refs.isEmpty()) null else Fact(obj.string("text"), refs)
            }
        }
        val validatedTodoData = mutableListOf<JsonObject>()
        val todos = root.array("todos").mapNotNull { element ->
            val obj = element.jsonObject
            obj.only("text", "owner", "due", "evidence")
            val text = obj.string("text")
            val refs = verifiedRefs(obj.array("evidence"))
            if (refs.isEmpty()) return@mapNotNull null
            val claimedOwner = obj.nullableString("owner")
            val owner = claimedOwner?.takeIf { name ->
                refs.any { ref -> ref.quote.contains(name) || byId[ref.segmentId].orEmpty().any { it.speaker == name } }
            }
            val due = (obj["due"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { date ->
                date.isNotBlank() && refs.any { it.quote.contains(date) }
            }
            validatedTodoData += buildJsonObject {
                put("text", text); put("owner", owner); put("due", due); put("evidence", json.encodeToJsonElement(refs))
            }
            val id = UUID.nameUUIDFromBytes((text + "\u0000" + owner + "\u0000" + due + "\u0000" + refs.map { it.segmentId }.sorted().joinToString("|")).toByteArray()).toString()
            TodoItem(id, text, owner, due, evidence = refs)
        }
        val validatedChapterData = mutableListOf<JsonObject>()
        val chapters = root.array("chapters").mapNotNull { element ->
            val obj = element.jsonObject
            obj.only("title", "evidence")
            val title = obj.string("title")
            val refs = verifiedRefs(obj.array("evidence"))
            if (refs.isEmpty()) return@mapNotNull null
            validatedChapterData += buildJsonObject { put("title", title); put("evidence", json.encodeToJsonElement(refs)) }
            val start = byId.getValue(refs.first().segmentId).first().segment.startMs
            start?.takeIf { it in 0..recording.durationMs }?.let { Chapter(it, title, refs) }
        }
        val validated = buildJsonObject {
            put("title", root.string("title", blank = true))
            put("abstract", abstract)
            put("abstractEvidence", json.encodeToJsonElement(abstractRefs))
            for ((category, entries) in facts) put(category, buildJsonArray {
                entries.forEach { fact -> add(buildJsonObject {
                    put("text", fact.text); put("evidence", json.encodeToJsonElement(fact.refs))
                }) }
            })
            put("todos", JsonArray(validatedTodoData))
            put("chapters", JsonArray(validatedChapterData))
        }
        Notes(validated, root.string("title", blank = true).takeIf { it.isNotBlank() }, abstract, abstractRefs, facts, todos, chapters)
    }

    private fun parseAnswer(input: JsonObject, scope: List<Part>): Answer = checked {
        val root = materializeEvidence(input, scope).jsonObject
        root.only("found", "answer", "evidence")
        val flag = root["found"]?.jsonPrimitive ?: invalid("问答缺少found标识")
        if (flag.isString) invalid("found应为布尔值")
        val found = flag.booleanOrNull ?: invalid("问答缺少found标识")
        val text = root.string("answer", blank = !found)
        val refs = references(root.array("evidence"), scope, required = found)
        if (!found && refs.isNotEmpty()) invalid("无依据回答不应包含证据")
        Answer(root, found, if (found) text else "逐字稿没有提到相关信息。", refs)
    }

    /** Model selects a source; the app copies its text. Legacy quoted references still require
     * exact matching, so a supplied fabricated quote is never replaced or silently accepted. */
    private fun materializeEvidence(value: JsonElement, scope: List<Part>): JsonElement = when (value) {
        is JsonObject -> if (value.keys == setOf("segmentId")) {
            val id = value.string("segmentId")
            val part = scope.firstOrNull { it.segment.id == id } ?: invalid("引用片段标识不存在")
            buildJsonObject { put("segmentId", id); put("quote", part.text) }
        } else JsonObject(value.mapValues { materializeEvidence(it.value, scope) })
        is JsonArray -> JsonArray(value.map { materializeEvidence(it, scope) })
        else -> value
    }

    private fun references(array: JsonArray, scope: List<Part>, required: Boolean = true): List<EvidenceRef> {
        if (required && array.isEmpty()) invalid("事实缺少有效引用")
        return array.map { element ->
            val obj = element.jsonObject
            obj.only("segmentId", "quote")
            val id = obj.string("segmentId"); val quote = obj.string("quote")
            val part = scope.firstOrNull { it.segment.id == id } ?: invalid("引用片段标识不存在")
            if (quote.isBlank() || !part.text.contains(quote)) invalid("引用不是该片段的连续原文")
            EvidenceRef(id, quote)
        }.distinct()
    }

    private fun JsonObject.only(vararg keys: String) { if ((this.keys - keys.toSet()).isNotEmpty()) invalid("包含未约定字段") }

    private fun allReferences(root: JsonObject): Set<EvidenceRef> {
        val refs = linkedSetOf<EvidenceRef>()
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> if (value.keys == setOf("segmentId", "quote"))
                    refs += EvidenceRef(value.string("segmentId"), value.string("quote")) else value.values.forEach(::visit)
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return refs
    }

    private fun validateMergeReferences(root: JsonObject, allowed: Set<EvidenceRef>?) {
        if (allowed != null && !allowed.containsAll(allReferences(root))) invalid("归并引用不属于已校验的输入")
    }

    private fun validateResultBudget(root: JsonObject) {
        // Any two accepted results must fit the next reduction; don't persist an unusable checkpoint.
        val budget = config.mergeChars ?: return
        if (root.toString().length > (budget - 4) / 2)
            throw AnalysisException("本段整理结果过长，未保存该段，请重试生成精简结果；已有进度保留", retryable = true)
    }

    private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: invalid("缺少${key}字段")
    private fun JsonObject.string(key: String, blank: Boolean = false): String {
        val p = this[key]?.jsonPrimitive ?: invalid("缺少${key}字段")
        if (!p.isString) invalid("${key}应为文本")
        val value = p.content.trim()
        if (!blank && value.isBlank()) invalid("${key}文本无效")
        return value
    }
    private fun JsonObject.nullableString(key: String): String? {
        val p = this[key] ?: invalid("缺少${key}字段")
        return if (p == JsonNull) null else string(key)
    }
    private fun invalid(reason: String): Nothing = throw AnalysisException(message("整理结果校验失败：$reason", "Could not verify the generated notes. Check the transcript and try again."), retryable = true)
    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (e: AnalysisException) { throw e }
        catch (_: Exception) { invalid("JSON字段类型不符合约定") }

    private fun fingerprint(recording: Recording, vararg parameters: String) = digest(buildJsonArray { listOf(
        MeetingPrompts.VERSION, "service-default-capacity-v2", config.baseUrl.toString(), config.model, config.chunkChars.toString(), config.mergeChars.toString(), config.reasoningEffort, config.outputLanguage,
        recording.id, transcriptHash(recording),recording.hardwareHighlightsStatus, *parameters,
    ).forEach { add(it) } }.toString())

    private suspend fun chat(system: String, data: JsonObject, localizeOutput: Boolean = true): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            put("reasoning_effort", config.reasoningEffort)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", if (localizeOutput && config.outputLanguage == "en") {
                        system.replace("用简体中文输出严格JSON", "用英语输出严格JSON") +
                            "\nWrite all generated prose in English. Keep source quotations, names, and due dates copied from the transcript unchanged."
                    } else system) })
                add(buildJsonObject { put("role", "user"); put("content", data.toString()) })
            })
        }.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(config.chatUrl).post(body)
        apiKey()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val call = client.newCall(builder.build())
        // Keep cancellation attached until the body finishes, including providers that send
        // headers/keepalive whitespace while still thinking. No app generation time limit.
        val raw = suspendCancellableCoroutine<String> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(AnalysisException(message("无法连接纪要服务", "Could not connect to the notes service."), retryable = true))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            when {
                                it.code in listOf(401, 403) -> throw AnalysisException(message("纪要服务拒绝了 API Key（${it.code}）", "The notes service rejected the API key (${it.code})."))
                                it.code == 429 -> throw AnalysisException(message("纪要服务限流，请稍后重试", "The notes service is rate limiting requests. Try again later."), retryable = true)
                                !it.isSuccessful -> throw AnalysisException(message("纪要服务返回错误 ${it.code}", "The notes service returned HTTP ${it.code}."), retryable = it.code >= 500)
                            }
                            it.body?.string().orEmpty()
                        }
                        if (cont.isActive) cont.resume(text)
                    } catch (e: Exception) {
                        val error = if (e is AnalysisException) e else AnalysisException(message("纪要服务连接中断，请重试", "The notes service connection dropped. Try again."), retryable = true)
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                }
            })
        }
        val message = try {
            val choice = json.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray.first().jsonObject
            when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
                "length" -> throw AnalysisException(message("纪要服务输出未完成（finish_reason=length），未保存不完整结果", "The notes service stopped at its output limit. Try again."), retryable = true)
                "content_filter" -> throw AnalysisException(message("纪要服务因内容策略停止了生成，未保存不完整结果", "The notes service stopped because of its content policy."))
            }
            choice["message"]!!.jsonObject
        } catch (e: AnalysisException) { throw e }
        catch (_: Exception) { throw AnalysisException(message("纪要服务响应格式无法识别", "The notes service returned an unrecognized response."), retryable = true) }
        if (message["refusal"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) throw AnalysisException(message("纪要服务未完成该请求", "The notes service declined the request."))
        runCatching { message["content"]!!.jsonPrimitive.content }.getOrElse { throw AnalysisException(message("纪要服务未返回文本", "The notes service did not return text."), retryable = true) }
    }

    private fun parseObject(content: String): JsonObject {
        val trimmed = content.trim()
        val text = if (trimmed.startsWith("```json") && trimmed.endsWith("```")) trimmed.removePrefix("```json").removeSuffix("```").trim()
            else if (trimmed.startsWith("```") && trimmed.endsWith("```")) trimmed.removePrefix("```").removeSuffix("```").trim() else trimmed
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw AnalysisException(message("纪要服务未返回完整JSON", "The notes service did not return complete JSON."), retryable = true) }
    }

    companion object {
        /** Kept as a compatibility constant; transcriptText no longer discards middle content. */
        const val MAX_TRANSCRIPT_CHARS = 60_000

        fun transcriptText(recording: Recording): String = recording.transcript?.segments.orEmpty().filter { it.text.isNotBlank() }.joinToString("\n") { s ->
            val time = s.startMs?.let { "[${clock(it)}] " }.orEmpty()
            val who = recording.speakerName(s.speakerId)?.let { "$it：" }.orEmpty()
            "[${s.id}] $time$who${s.text.trim()}"
        }

        fun transcriptHash(recording: Recording): String = digest(buildJsonObject {
            put("durationMs", recording.durationMs)
            // Preserve legacy revisions for unknown/verified-empty recordings.
            if(!recording.hardwareHighlights.isNullOrEmpty()) put("hardwareHighlights",buildJsonArray {
                recording.hardwareHighlights.sortedBy {it.positionMs}.forEach {add(buildJsonObject {put("id",it.id);put("positionMs",it.positionMs)})}
            })
            put("segments", buildJsonArray { recording.transcript?.segments.orEmpty().forEach { segment -> add(buildJsonObject {
                put("id", segment.id); put("text", segment.text); put("startMs", segment.startMs); put("endMs", segment.endMs)
                put("speakerId", segment.speakerId); put("speaker", recording.speakerName(segment.speakerId))
            }) } })
        }.toString())

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun clock(ms: Long): String {
            val total = ms / 1000; val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
        fun parseClock(text: String): Long? {
            val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
            if (parts.any { it < 0 } || parts.lastOrNull()?.let { it >= 60 } == true) return null
            return when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]) * 1000
                3 -> if (parts[1] >= 60) null else (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                else -> null
            }
        }
    }
}
