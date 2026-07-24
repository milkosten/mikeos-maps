package com.mikeos.core.net

import android.util.Log
import com.mikeos.core.agent.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MIKEAGENT.md §1c step 3 (REASON+ACT) — the agentic loop, driven CLIENT-SIDE.
 *
 * The daemon (`POST /api/agent/chat`) is a plain chat brain; we do NOT assume it does
 * server-side tool-calling. Instead we use a JSON-ACTION protocol:
 *
 *  1. Send the system-context + the skills list, and instruct the model to reply with
 *     STRICT JSON: {"thought": "...", "actions": [{"skill":"name","args":{...}}], "done": bool}
 *  2. Parse the reply (tolerantly — strip code fences), execute each action through the
 *     [SkillRegistry], and collect the results.
 *  3. Append the results as the next user turn and loop.
 *  4. Stop when the model says `"done": true`, when there are no actions, or after
 *     [MikeAgentConfig.maxBrainIterations] (default 18) iterations.
 *
 * The daemon's own `session_id` is threaded through so the brain keeps its context
 * across the loop's turns. Best-effort throughout: any failure ends the loop cleanly.
 */
class DaemonBrain(
    private val baseUrl: String,
    private val token: String,
    private val maxIterations: Int = 18,
) {
    private val tag = "DaemonBrain"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Loopback-trusting client, scoped to 127.0.0.1 (the daemon's self-signed cert).
    private val client: OkHttpClient = loopbackTrustingClient(baseUrl).newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()

    /** One action the model asked us to take. */
    data class Action(val skill: String, val args: JSONObject)

    /** Outcome of a full agentic run: what happened, for the REMEMBER phase. */
    data class BrainResult(
        val iterations: Int,
        val executed: List<Pair<Action, String>>,
        val lastThought: String?,
    )

    /**
     * Run the agentic loop.
     *
     * @param systemContext the full SYSTEM-CONTEXT for this beat (soul + perception +
     *   skills + hive inbox), built by [com.mikeos.core.agent.SystemContext].
     * @param skills the registry whose skills the model may call.
     * @return a [BrainResult] summarising the run. Never throws.
     */
    suspend fun run(systemContext: String, skills: SkillRegistry): BrainResult =
        withContext(Dispatchers.IO) {
            val executed = mutableListOf<Pair<Action, String>>()
            var sessionId: String? = null
            var lastThought: String? = null

            // First turn: the system-context + the strict-JSON protocol instruction.
            var nextMessage = systemContext + "\n\n" + protocolInstruction()

            var iter = 0
            while (iter < maxIterations) {
                iter++
                val reply = chat(nextMessage, sessionId) ?: break
                sessionId = reply.sessionId ?: sessionId
                val raw = reply.response ?: break

                val parsed = parseActionJson(raw)
                if (parsed == null) {
                    Log.w(tag, "unparseable brain reply on iter $iter; stopping")
                    break
                }
                lastThought = parsed.optString("thought").ifBlank { lastThought }

                val actions = extractActions(parsed)
                if (actions.isEmpty()) break

                // Execute each action; collect results to feed back.
                val resultLines = StringBuilder("Results of your actions:\n")
                for (action in actions) {
                    val skill = skills[action.skill]
                    val result = if (skill == null) {
                        "ERROR: unknown skill '${action.skill}'"
                    } else {
                        runCatching { skill.run(action.args) }
                            .getOrElse { "ERROR: ${it.message}" }
                    }
                    executed.add(action to result)
                    resultLines.append("- ${action.skill}: $result\n")
                }

                if (parsed.optBoolean("done", false)) break

                // Feed results back as the next user turn and loop.
                nextMessage = resultLines.toString() +
                    "\nContinue if there is more to do, otherwise reply with " +
                    """{"thought":"...","actions":[],"done":true}."""
            }

            BrainResult(iterations = iter, executed = executed, lastThought = lastThought)
        }

    private data class ChatReply(val response: String?, val sessionId: String?)

    private fun chat(message: String, sessionId: String?): ChatReply? = runCatching {
        val payload = json.encodeToString(
            AgentChatRequest.serializer(),
            AgentChatRequest(message = message, sessionId = sessionId),
        )
        val req = Request.Builder()
            .url("$baseUrl/api/agent/chat")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString(AgentChatResponse.serializer(), body)
            }.getOrNull()
            if (!resp.isSuccessful) {
                Log.w(tag, "chat HTTP ${resp.code}: ${parsed?.error ?: body.take(200)}")
                return null
            }
            ChatReply(parsed?.response, parsed?.sessionId ?: sessionId)
        }
    }.getOrNull()

    /** The strict-JSON protocol appended to the first turn. */
    private fun protocolInstruction(): String = """
        ## How to respond THIS beat
        Reply with STRICT JSON and nothing else. No prose outside the JSON, no code fences.
        Shape:
        {"thought": "brief reasoning", "actions": [{"skill": "skill_name", "args": {...}}], "done": true|false}
        - Put each tool you want to call in "actions" with its exact name and args.
        - Set "done": true when you have nothing left to do this beat (an empty "actions"
          list with "done": true is how you choose to do nothing — a valid choice).
        - After I run your actions I will send you the results; you may then act again.
    """.trimIndent()

    /**
     * Tolerant JSON parse: strips ``` / ```json fences and grabs the outermost {...}
     * so a chatty model that wraps its JSON still parses.
     */
    private fun parseActionJson(raw: String): JSONObject? {
        var s = raw.trim()
        // Strip code fences.
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        // Grab the outermost object if there is surrounding prose.
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        return runCatching { JSONObject(s) }.getOrNull()
    }

    private fun extractActions(parsed: JSONObject): List<Action> {
        val arr: JSONArray = parsed.optJSONArray("actions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("skill").ifBlank { o.optString("name") }
            if (name.isBlank()) return@mapNotNull null
            val args = o.optJSONObject("args") ?: JSONObject()
            Action(skill = name, args = args)
        }
    }
}
