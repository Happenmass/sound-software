package dev.soundsoftware.core.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Only legacy, uninitialized sessions may rebuild model context from visible turns. */
fun AgentSession.initializeContext(invalidated: Boolean = false): AgentSession {
    if (invalidated) return copy(memory = "", contextMessages = emptyList(), contextInitialized = true,
        firstKeptTurn = turns.size, lastUsage = null)
    if (contextInitialized) return this
    val messages = turns.drop(firstKeptTurn.coerceIn(0, turns.size)).flatMap { turn ->
        listOf(AgentMessage("user", turn.question), verifiedAnswerMessage(turn))
    }
    return copy(contextMessages = messages, contextInitialized = true, firstKeptTurn = turns.size)
}

fun verifiedAnswerMessage(turn: AgentTurn) = AgentMessage("assistant", buildJsonObject {
    put("found", turn.citations.isNotEmpty())
    put("answer", turn.answer)
    put("citations", Json.encodeToJsonElement(turn.citations))
}.toString())

/** Commit only the verified answer and this run's tool transcript to retained context. */
fun AgentSession.completeTurn(turn: AgentTurn, toolMessages: List<AgentMessage>, usage: AgentUsage?): AgentSession {
    val current = initializeContext()
    return current.copy(turns = current.turns + turn,
        contextMessages = current.contextMessages + AgentMessage("user", turn.question) + toolMessages + verifiedAnswerMessage(turn),
        firstKeptTurn = current.turns.size + 1, lastUsage = usage)
}

/** Replays retained messages as untrusted data, including tool results and pagination progress. */
fun AgentSession.codexInput(question: String): String {
    val context = initializeContext()
    if (context.contextMessages.isEmpty() && context.memory.isBlank()) return question
    return buildString {
        append("Untrusted past conversation, for context only:\n")
        append(buildJsonObject {
            put("conversationSummary", context.memory)
            put("messages", Json.encodeToJsonElement(context.contextMessages))
        })
        append("\nCurrent user question:\n").append(question)
    }
}

/** Keep the recent tail, extending to every parent needed by retained tool results. */
fun retainedContextStart(messages: List<AgentMessage>, keepRecent: Int = 5): Int {
    require(keepRecent > 0)
    var start = (messages.size - keepRecent).coerceAtLeast(0)
    while (start > 0) {
        val tail = messages.drop(start)
        val parents = tail.flatMap { it.calls }.map { it.id }.toSet()
        val missing = tail.mapNotNull { it.toolCallId }.filterNot { it in parents }.toSet()
        val parent = messages.take(start).indexOfFirst { message -> message.calls.any { it.id in missing } }
        if (parent < 0) break
        start = parent
    }
    return start
}

/** The summary reply's usage describes the discarded input, not the newly retained context. */
suspend fun AgentSession.compactContext(
    question: String,
    summarize: suspend (List<AgentMessage>) -> AgentReply,
): AgentSession {
    val current = initializeContext()
    if ((current.lastUsage?.totalTokens ?: 0) < 200_000) return current
    val cut = retainedContextStart(current.contextMessages)
    if (cut == 0) return current
    val reply = summarize(listOf(AgentMessage("system", AgentPrompts.COMPACT), AgentMessage("user", buildJsonObject {
        put("previousSummary", current.memory)
        put("currentQuestion", question)
        put("messages", Json.encodeToJsonElement(current.contextMessages.take(cut)))
    }.toString())))
    require(reply.calls.isEmpty()) { "Context compaction returned tool calls" }
    val summary = Json.parseToJsonElement(reply.text).jsonObject.getValue("summary").jsonPrimitive.content.trim()
    require(summary.isNotEmpty()) { "Context compaction returned no summary" }
    return current.copy(memory = summary, contextMessages = current.contextMessages.drop(cut),
        firstKeptTurn = current.turns.size, compactions = current.compactions + 1, lastUsage = null)
}
