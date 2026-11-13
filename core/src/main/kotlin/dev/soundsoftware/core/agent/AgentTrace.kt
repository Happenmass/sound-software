package dev.soundsoftware.core.agent

import kotlinx.serialization.Serializable

/** Observable work only: never stores a provider's private reasoning or request credentials. */
@Serializable
data class AgentTraceStep(
    val id: Int,
    val kind: String,
    val title: String,
    val detail: String = "",
    val status: String = "running",
    val elapsedMs: Long = 0,
    val toolName: String? = null,
    val arguments: String? = null,
    val result: String? = null,
    val usage: AgentUsage? = null,
)
