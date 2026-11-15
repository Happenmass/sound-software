package dev.soundsoftware.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import dev.soundsoftware.core.model.RecordingHighlight

@Serializable data class RecordingCitation(val recordingId: String, val segmentId: String, val quote: String, val revision: String,
    val title: String = "", val startMs: Long? = null, val speaker: String? = null,
    val hardwareHighlights: List<RecordingHighlight> = emptyList())
@Serializable data class AgentTurn(val question: String, val answer: String, val citations: List<RecordingCitation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val steps: List<String> = emptyList(), val trace: List<AgentTraceStep> = emptyList(),
    val evidenceWarning: String? = null)
@Serializable data class AgentSession(val id: String = UUID.randomUUID().toString(), val turns: List<AgentTurn> = emptyList(),
    val memory: String = "", val firstKeptTurn: Int = 0, val compactions: Int = 0,
    val contextMessages: List<AgentMessage> = emptyList(), val contextInitialized: Boolean = false,
    val lastUsage: AgentUsage? = null)
@Serializable data class AgentUsage(val promptTokens: Long, val completionTokens: Long, val totalTokens: Long = promptTokens + completionTokens)
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)
data class AgentReply(val text: String = "", val calls: List<ToolCall> = emptyList(), val usage: AgentUsage? = null)
@Serializable data class AgentMessage(val role: String, val content: String = "", val toolCallId: String? = null,
    val calls: List<WireToolCall> = emptyList())
@Serializable data class WireToolCall(val id: String, val name: String, val arguments: String)
interface AgentModel {
    suspend fun complete(messages: List<AgentMessage>, tools: Boolean): AgentReply
    suspend fun completeStreaming(messages: List<AgentMessage>, onDraft: (String) -> Unit): AgentReply = complete(messages, true)
}
interface RecordingTools {
    /** Only declared, bounded, read-only tools; no SQL, file paths, network or hardware actions. */
    suspend fun execute(name: String, arguments: JsonObject): JsonObject
    /** Resolve against the current index, not model-written metadata. */
    suspend fun resolve(ref: RecordingCitation): RecordingCitation?
}
