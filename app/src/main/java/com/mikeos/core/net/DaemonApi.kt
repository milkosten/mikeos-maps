package com.mikeos.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the on-device MikeDaemon HTTP API.
 *
 *   POST /api/agent/chat  body { message, session_id? }
 *        200 { session_id, response, tool_calls, tokens_used, duration_ms }
 *        4xx/5xx { error }
 *
 * NOTE: we do NOT use server-side tool-calling. The daemon is a plain chat brain;
 * the agentic loop is driven client-side via the JSON-action protocol in
 * [com.mikeos.core.net.DaemonBrain]. `tool_calls` is parsed only for completeness.
 */

@Serializable
data class AgentChatRequest(
    val message: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class ToolCall(
    val name: String? = null,
)

@Serializable
data class AgentChatResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val response: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tokens_used") val tokensUsed: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val error: String? = null,
)
