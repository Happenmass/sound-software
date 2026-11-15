package dev.soundsoftware.core.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

@Serializable
data class VerifiedRecordingAnswer(val answer: String, val citations: List<RecordingCitation>, val evidenceWarning: String? = null)

suspend fun verifyRecordingAnswer(
    text: String, receipts: Set<RecordingCitation>, tools: RecordingTools, outputLanguage: String,
): VerifiedRecordingAnswer {
    val json = Json { ignoreUnknownKeys = false }
    fun message(chinese: String, english: String) = if (outputLanguage == "en") english else chinese
    val result = json.parseToJsonElement(text).jsonObject
    require(result.keys == setOf("answer", "found", "citations")) { message("回答格式不完整，请重试", "The answer format is incomplete. Try again.") }
    val answer = result.getValue("answer").jsonPrimitive.content.trim()
    val found = result.getValue("found").jsonPrimitive.boolean
    require(answer.isNotEmpty()) { message("回答正文为空，请重试", "The answer is empty. Try again.") }
    if (!found) return VerifiedRecordingAnswer(message("没有找到足够的录音原文依据，可以补充日期、人名或关键词再查。", "No supporting passages found. Try adding a date, name, or keyword."), emptyList())
    val refs = result.getValue("citations").jsonArray.distinct()
    val resolved = refs.mapNotNull { element ->
        currentCoroutineContext().ensureActive()
        val ref = try { json.decodeFromJsonElement<RecordingCitation>(element) }
        catch (_: SerializationException) { return@mapNotNull null }
        if (ref.quote.isBlank() || receipts.none { it.recordingId == ref.recordingId && it.segmentId == ref.segmentId &&
                it.revision == ref.revision && it.quote.contains(ref.quote) }) return@mapNotNull null
        tools.resolve(ref)
    }
    val warning = when {
        resolved.isEmpty() -> message("这份回答没有通过核对的原文来源，内容待核实。", "No citations could be verified. Check the answer against your recordings.")
        resolved.size < refs.size -> message("有 ${refs.size - resolved.size} 条引用未通过核对，已省略。", "${refs.size - resolved.size} citations could not be verified and were omitted.")
        else -> null
    }
    return VerifiedRecordingAnswer(answer, resolved, warning)
}


