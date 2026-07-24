package com.mikeos.core.agent

import org.json.JSONObject

/**
 * MIKEAGENT.md §1b — a SKILL: something the agent can *do*. The LLM sees the skill
 * list (name + description + params) in the SYSTEM-CONTEXT and calls skills itself
 * via the JSON-action protocol; the runtime executes [run] and feeds results back.
 *
 * @property name        stable identifier the LLM uses in an action, e.g. `hive_send`.
 * @property description one line telling the LLM what it does and when to use it.
 * @property paramsSchema a JSON string describing the args (object of name->type/desc).
 *   Rendered verbatim into the prompt; it is documentation for the model, not enforced.
 * @property run         suspend fn: given the parsed args, do the thing and return a
 *   short human-readable result string (fed back to the model as the next turn).
 */
class Skill(
    val name: String,
    val description: String,
    val paramsSchema: String,
    val run: suspend (args: JSONObject) -> String,
)

/**
 * Holds the skills available to an agent and renders them for the prompt.
 * Apps register their own skills; the four UNIVERSAL skills are added by the
 * runtime via [withUniversal] so every agent always has them.
 */
class SkillRegistry(skills: List<Skill> = emptyList()) {
    private val map = LinkedHashMap<String, Skill>()

    init {
        skills.forEach { register(it) }
    }

    /** Add or replace a skill by name. Returns this for chaining. */
    fun register(skill: Skill): SkillRegistry {
        map[skill.name] = skill
        return this
    }

    fun register(skills: List<Skill>): SkillRegistry {
        skills.forEach { register(it) }
        return this
    }

    operator fun get(name: String): Skill? = map[name]

    fun all(): List<Skill> = map.values.toList()

    /**
     * Render the skills for the SYSTEM-CONTEXT {skills} slot. Each line is:
     *   `- name(params) — description`
     * The four universal skills are described separately in the template's prose,
     * so by convention they are omitted here (see [MikeAgent]); this renders exactly
     * the skills handed to it.
     */
    fun renderForPrompt(): String {
        if (map.isEmpty()) return "(no app-specific skills)"
        return map.values.joinToString("\n") { skill ->
            "- ${skill.name}(${paramNames(skill.paramsSchema)}) — ${skill.description}"
        }
    }

    private fun paramNames(schema: String): String = runCatching {
        JSONObject(schema).keys().asSequence().joinToString(", ")
    }.getOrDefault("")

    companion object {
        /**
         * The UNIVERSAL skills every agent always has (MIKEAGENT.md §1b).
         * The runtime wires their [Skill.run] to real behaviour at install time via
         * the supplied lambdas, since they need the hive socket / soul store.
         *
         * @param siblings the statically-configured sibling agents this phone hosts
         *   (from MikeAgentConfig.siblings); `ask_siblings` broadcasts to these.
         * @param selfName this agent's own name, stamped into `ask_siblings` bodies as
         *   `from` so a replying sibling knows whom to answer.
         */
        fun universal(
            hiveSend: suspend (to: String, type: String, body: String) -> String,
            remember: suspend (note: String) -> String,
            recall: suspend (query: String) -> String,
            notify: suspend (text: String) -> String,
            location: suspend () -> String,
            siblings: List<String> = emptyList(),
            selfName: String = "",
        ): List<Skill> = listOf(
            Skill(
                name = "location",
                description = "The user's CURRENT location (lat, lon, city) from the device's ONE " +
                    "shared GPS fix. Use this whenever you need where Mike is — it is a device fact, " +
                    "not a sibling's data, so NEVER ask another agent for the location; call this.",
                paramsSchema = "{}",
                run = { _ -> location() },
            ),
            Skill(
                name = "hive_send",
                description = "Message a sibling agent on the hive. Use proactively when you learn something a sibling would act on.",
                paramsSchema = """{"to":"agent name","type":"message type e.g. place.arrived","body":"the message"}""",
                run = { args ->
                    hiveSend(
                        args.optString("to"),
                        args.optString("type"),
                        args.optString("body"),
                    )
                },
            ),
            Skill(
                name = "remember",
                description = "Keep a note in long-term memory so you recall it in future beats.",
                paramsSchema = """{"note":"what to remember"}""",
                run = { args -> remember(args.optString("note")) },
            ),
            Skill(
                name = "recall",
                description = "Look something up in your long-term memory by keyword.",
                paramsSchema = """{"query":"what to look up"}""",
                run = { args -> recall(args.optString("query")) },
            ),
            Skill(
                name = "notify",
                description = "Reach the user directly with a short notification. Use sparingly.",
                paramsSchema = """{"text":"the notification text"}""",
                run = { args -> notify(args.optString("text")) },
            ),
            Skill(
                name = "ask_siblings",
                description = "Ask a question of your sibling agents. Give 'to' to ask one " +
                    "sibling, or omit it to broadcast to all siblings. FIRE-AND-FORGET-" +
                    "THEN-LISTEN: this only SENDS the question; replies arrive later as " +
                    "inbound agent.answer messages you handle on a future beat — it is NOT synchronous.",
                paramsSchema = """{"question":"what to ask","to":"optional sibling name; omit to broadcast to all"}""",
                run = { args ->
                    val question = args.optString("question")
                    if (question.isBlank()) {
                        return@Skill "ask_siblings needs a 'question'"
                    }
                    val to = args.optString("to").takeIf { it.isNotBlank() }
                    val body = JSONObject()
                        .put("question", question)
                        .put("from", selfName)
                        .toString()
                    if (to != null) {
                        hiveSend(to, "agent.question", body)
                    } else {
                        val targets = siblings.filter { it.isNotBlank() && it != selfName }
                        if (targets.isEmpty()) {
                            "no siblings configured to broadcast to"
                        } else {
                            targets.forEach { hiveSend(it, "agent.question", body) }
                            "asked ${targets.size} sibling(s): ${targets.joinToString(", ")} " +
                                "(listening for agent.answer replies on later beats)"
                        }
                    }
                },
            ),
        )
    }
}
