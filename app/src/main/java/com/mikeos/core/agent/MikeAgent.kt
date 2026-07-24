package com.mikeos.core.agent

import android.content.Context
import android.util.Log
import com.mikeos.core.MikeAgentConfig
import com.mikeos.core.hive.HiveCred
import com.mikeos.core.hive.HiveIdentity
import com.mikeos.core.hive.HiveLog
import com.mikeos.core.hive.HiveMessage
import com.mikeos.core.hive.HiveSocket
import com.mikeos.core.hive.SiblingDirectory
import com.mikeos.core.net.DaemonBrain
import com.mikeos.core.net.loopbackTrustingClientPublic
import com.mikeos.core.runtime.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * MIKEAGENT.md §1c — the HEARTBEAT / agentic loop, tying everything together.
 *
 * One installed instance per app process. [beat] runs the closed loop:
 *   PERCEIVE → RECALL → build SYSTEM-CONTEXT → DaemonBrain agentic loop → REMEMBER.
 *
 * Best-effort: [beat] never throws. Change-gated: if perception is unchanged since
 * last beat AND the hive inbox is empty, the brain is not called (idle apps don't
 * spam the daemon).
 *
 * Apps do not construct this directly — they call [MikeAgent.install] once and then
 * start the [com.mikeos.core.runtime.HeartbeatService].
 */
class MikeAgent internal constructor(
    private val appContext: Context,
    val config: MikeAgentConfig,
    private val soulStore: SoulStore,
    /** The agent's tools. Public so the Agent Inspector can list them. */
    val skills: SkillRegistry,
    val cred: HiveCred?,
    val hiveSocket: HiveSocket?,
    /** Persistent log of every hive message sent/received. Read by the inspector UI. */
    val hiveLog: HiveLog,
) {
    private val tag = "MikeAgent"

    /** The agent's soul (persona + goals + names). Public so the inspector can show it. */
    val soul: Soul get() = soulStore.soul

    private val brain = DaemonBrain(
        baseUrl = config.daemonBaseUrl,
        token = config.daemonToken,
        maxIterations = config.maxBrainIterations,
    )

    // Change-gate: hash of the last perception the brain actually saw.
    @Volatile
    private var lastPerceptionKey: String? = null

    /**
     * Hive collaboration protocol (Phase 2) — the LIVE directory of who's online and what
     * each sibling offers, populated from inbound `capability.announce` messages. Injected
     * into the SYSTEM-CONTEXT so the brain reasons over REAL capabilities. Public so the
     * inspector can show it.
     */
    val directory = SiblingDirectory()

    /**
     * Dedicated scope for hive-protocol work (announce, answering questions). This runs
     * OFF the socket's onMessage coroutine — a handler that sent inline used to deadlock
     * (send took the same lock the fetch that invoked it held). See HiveSocket.onMessage.
     */
    private val hiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedup: agent.question bodies we've already answered, so we never answer twice.
    private val answeredQuestions = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // Beat counter so we re-announce our capabilities periodically (late joiners).
    @Volatile private var beatsSinceStart = 0L

    @Volatile private var protocolWired = false

    // The user's interest context (global + this app's per-app), from mikeos-ai-cloud.
    // Injected into every beat's prompt so the assistant personalises. Cached + refreshed
    // occasionally (cheap, off the beat). Empty when personalisation is off / not set.
    @Volatile private var interestsBlock: String = ""
    @Volatile private var interestsFetchedMs: Long = 0L

    /** Open the live hive connection. Called by the foreground service. */
    fun connectHive() {
        hiveSocket?.connect()
        wireHiveProtocol()
    }

    /**
     * Hive collaboration protocol (Phase 2 + 3). Registers the core inbound handler
     * (ingest `capability.announce` into the directory; deterministically answer any
     * `agent.question` with an `agent.answer` grounded in THIS agent's own skills) and
     * announces our own capabilities to all siblings. Idempotent.
     *
     * Everything dispatches onto [hiveScope] — NEVER runs the send inline on the socket's
     * onMessage coroutine (that path self-deadlocked). Best-effort throughout.
     */
    private fun wireHiveProtocol() {
        val socket = hiveSocket ?: return
        if (protocolWired) {
            // Already wired; just re-announce (a fresh connect / reconnect).
            hiveScope.launch { runCatching { announceCapabilities() } }
            return
        }
        protocolWired = true

        socket.onMessage { msg ->
            // Return immediately; do the real work off the onMessage coroutine.
            hiveScope.launch { runCatching { onHiveMessage(msg) } }
        }
        // Announce who we are + what we offer to everyone on the hive.
        hiveScope.launch { runCatching { announceCapabilities() } }
        Log.i(tag, "hive collaboration protocol wired")
    }

    /** Route one inbound hive message through the collaboration protocol. */
    private suspend fun onHiveMessage(msg: HiveMessage) {
        when (msg.type) {
            "capability.announce" -> ingestAnnounce(msg)
            "agent.question" -> answerQuestion(msg)
            else -> { /* other types flow to the beat via the inbox as before */ }
        }
    }

    /** Learn a sibling's capabilities from its announce and record them in the directory. */
    private fun ingestAnnounce(msg: HiveMessage) {
        val o = runCatching { JSONObject(msg.body) }.getOrNull() ?: return
        val app = o.optString("app").ifBlank { msg.from.substringAfterLast('/') }
        if (app.isBlank() || app == soul.appName) return
        val persona = o.optString("persona")
        val offers = o.optString("offers")
        val skills = runCatching {
            val arr = o.optJSONArray("skills") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = s.optString("name")
                if (n.isBlank()) null else "$n — ${s.optString("description")}"
            }
        }.getOrDefault(emptyList())
        val isNew = app !in directory.knownApps()
        directory.upsert(app, persona, offers, skills)
        Log.i(tag, "capability.announce from $app (${skills.size} skills); directory now ${directory.knownApps()}")
        // Good citizen: when we first hear a NEW sibling, reply with OUR announce so
        // directories converge — directly to them only, so it can't fan out into a loop.
        if (isNew) hiveScope.launch { runCatching { announceCapabilitiesTo(msg.from) } }
    }

    /**
     * Deterministically answer an inbound `agent.question` using THIS agent's own skills,
     * then hive_send an `agent.answer` back to the asker. Best-effort, deduped, never throws.
     * Runs a FOCUSED one-shot brain call (soul + this agent's skills + the sibling's
     * question) so the agent can call a skill if it helps, then extract a concise answer.
     */
    private suspend fun answerQuestion(msg: HiveMessage) {
        val socket = hiveSocket ?: return
        val question = extractQuestion(msg.body)
        if (question.isBlank()) return
        // Dedup on (asker + question) so the same ask isn't answered twice.
        val dedupKey = "${msg.from}|$question"
        if (!answeredQuestions.add(dedupKey)) return
        Log.i(tag, "agent.question from ${msg.from}: ${question.take(120)}")

        val answer = runCatching { reasonAnswer(msg.from, question) }
            .getOrElse { "I couldn't work that out right now." }
            .ifBlank { "I don't have anything on that right now." }

        val body = JSONObject()
            .put("answer", answer)
            .put("in_reply_to", question)
            .toString()
        runCatching { socket.send(msg.from, "agent.answer", body) }
            .onSuccess { Log.i(tag, "agent.answer → ${msg.from}: ${answer.take(120)}") }
            .onFailure { Log.w(tag, "agent.answer send failed: ${it.message}") }
    }

    /**
     * A one-shot, focused brain run that answers a sibling's question from our own skills.
     * We DON'T expose hive_send here (we send the answer ourselves, deterministically), so
     * the brain just gathers facts via app skills and states a concise answer as its final
     * thought. Returns that thought, or "" if the brain produced nothing usable.
     */
    private suspend fun reasonAnswer(from: String, question: String): String {
        val focused = buildString {
            append("You are ${soul.agentName}, the agent inside ${soul.appName} on ")
            append("${config.userName}'s MikeOS phone.\n")
            append("Your persona: ${soul.persona}\n\n")
            append("A SIBLING agent (${from.substringAfterLast('/')}) asked you this over the hive:\n")
            append("  \"$question\"\n\n")
            append("## Your skills — CALL these to look up the real answer\n")
            append(skills.renderForPrompt())
            append("\n\n")
            append("Answer the sibling's question FACTUALLY, grounded in YOUR OWN data. ")
            append("FIRST call whichever skill(s) above fetch the facts you need (do not ")
            append("answer from memory when a skill can get the real data). Do NOT call ")
            append("hive_send/ask_siblings and do NOT ask other siblings — the runtime sends ")
            append("your answer for you. After the skill results come back, put a concise, ")
            append("self-contained reply (1-3 sentences) in your \"thought\" and set ")
            append("done:true. Only if no skill is relevant should you answer directly; if ")
            append("you genuinely have nothing, say so plainly.")
        }
        val result = brain.run(focused, skills)
        return result.lastThought?.trim().orEmpty()
    }

    /** Pull the question text from an agent.question body (JSON `{"question":…}` or raw). */
    private fun extractQuestion(body: String): String {
        val fromJson = runCatching {
            val o = JSONObject(body)
            o.optString("question").takeIf { it.isNotBlank() }
                ?: o.optString("text").takeIf { it.isNotBlank() }
        }.getOrNull()
        return (fromJson ?: body).trim()
    }

    /** Build our own capability.announce body from the soul + app skill descriptions. */
    private fun buildAnnounceBody(): String {
        val appSkills = skills.all().filter { it.name !in UNIVERSAL_SKILL_NAMES }
        val skillsArr = JSONArray()
        appSkills.forEach { s ->
            skillsArr.put(JSONObject().put("name", s.name).put("description", s.description))
        }
        val offers = buildString {
            append(shortPersona(soul.persona))
            val descs = appSkills.take(4).joinToString("; ") { it.description }
            if (descs.isNotBlank()) {
                if (isNotEmpty()) append(" — ")
                append(descs)
            }
        }.take(240)
        return JSONObject()
            .put("app", soul.appName)
            .put("persona", shortPersona(soul.persona))
            .put("offers", offers)
            .put("skills", skillsArr)
            .toString()
    }

    /** Broadcast our capabilities to every sibling on the hive. */
    private suspend fun announceCapabilities() {
        val socket = hiveSocket ?: return
        val n = socket.broadcast("capability.announce", buildAnnounceBody())
        Log.i(tag, "capability.announce broadcast to $n sibling(s)")
    }

    /** Send our capabilities to ONE sibling (used to converge directories, loop-safe). */
    private suspend fun announceCapabilitiesTo(to: String) {
        val socket = hiveSocket ?: return
        runCatching { socket.send(to, "capability.announce", buildAnnounceBody()) }
    }

    /** First sentence / first ~140 chars of the persona, for a compact announce/offer. */
    private fun shortPersona(persona: String): String {
        val firstSentence = persona.trim().substringBefore(". ").trim()
        val base = if (firstSentence.length in 8..160) firstSentence else persona.trim()
        return base.replace(Regex("\\s+"), " ").take(160)
    }

    /**
     * Run ONE beat.
     *
     * @param perception the app's current domain snapshot (what the agent perceives).
     * @param force run even if nothing changed (default false → change-gated).
     * @return the last thought from the brain, or null if the beat was skipped/idle.
     */
    suspend fun beat(perception: String, force: Boolean = false): String? {
        return runCatching { beatInner(perception, force) }
            .getOrElse {
                Log.w(tag, "beat failed: ${it.message}")
                null
            }
    }

    private suspend fun beatInner(perception: String, force: Boolean): String? {
        // 1. PERCEIVE — perception arg + drain the hive inbox.
        val inbox: List<HiveMessage> = hiveSocket?.drainInbox() ?: emptyList()

        // Phase 2: re-announce our capabilities periodically (~every 10th forced beat) so
        // late joiners learn what we offer. Off-loop, best-effort.
        beatsSinceStart++
        if (force && protocolWired && beatsSinceStart % 10L == 0L) {
            hiveScope.launch { runCatching { announceCapabilities() } }
        }

        // Change-gate: skip the brain if nothing changed AND inbox empty.
        val perceptionKey = perception.hashCode().toString()
        if (!force && inbox.isEmpty() && perceptionKey == lastPerceptionKey) {
            return null
        }
        lastPerceptionKey = perceptionKey

        // Personalisation: refresh the user's interest context occasionally (off the beat,
        // best-effort). The cached value is used this beat; a fresh one lands next beat.
        if (System.currentTimeMillis() - interestsFetchedMs > INTERESTS_TTL_MS) {
            hiveScope.launch { runCatching { refreshInterests() } }
        }

        // 2. RECALL — soul (persona, goals) + relevant memory (keyed on perception).
        val memoryBlock = soulStore.relevantMemoryBlock(query = firstWords(perception))
        val goalsBlock = soulStore.goalsBlock()

        // 3. Build the SYSTEM-CONTEXT (single source of truth).
        val systemContext = SystemContext.build(
            agentName = soul.agentName,
            appName = soul.appName,
            userName = config.userName,
            persona = soul.persona,
            goals = goalsBlock,
            memory = memoryBlock,
            perception = perception,
            skills = skills.renderForPrompt(),
            // Phase 2: inject the LIVE directory (who's online + what each offers). Fall
            // back to the static config list when we haven't heard any announces yet.
            siblings = directory.render().ifBlank {
                if (config.siblings.isEmpty()) "(discovering them on the hive)"
                else config.siblings.joinToString(", ")
            },
            hiveInbox = renderInbox(inbox),
            // Step 4: the user's standing context (global + this app's per-app), so every
            // assistant personalises to Mike's places/interests/preferences.
            interests = interestsBlock,
        )

        // 4. REASON+ACT — the DaemonBrain agentic loop executes skills (incl. hive_send).
        val result = brain.run(systemContext, skills)

        // 5. REMEMBER — keep a compact trace if anything happened worth keeping.
        if (result.executed.isNotEmpty()) {
            val summary = result.executed.joinToString("; ") { (a, _) -> a.skill }
            soulStore.remember(
                text = "beat: ${result.lastThought ?: "acted"} [$summary]",
                tags = "beat",
            )
        }
        return result.lastThought
    }

    private fun renderInbox(inbox: List<HiveMessage>): String {
        if (inbox.isEmpty()) return "(no new messages)"
        return inbox.joinToString("\n") { "- from ${it.from} [${it.type}]: ${it.body}" }
    }

    private fun firstWords(s: String): String =
        s.trim().split(Regex("\\s+")).take(6).joinToString(" ")

    /** Pull the user's interest context (global + this app's per-app) from mikeos-ai-cloud. */
    private suspend fun refreshInterests() {
        interestsFetchedMs = System.currentTimeMillis()   // set first so a failure won't hammer
        val key = cred?.agentKey ?: return
        val ctx = com.mikeos.core.net.AiContext.fetch(key) ?: return
        interestsBlock = ctx.promptBlock(soul.appName, config.userName)
    }

    companion object {
        /** How long a fetched interest context stays cached before a background refresh. */
        private const val INTERESTS_TTL_MS = 10 * 60_000L
        /** The universal skills the runtime adds to every agent (excluded from announces). */
        private val UNIVERSAL_SKILL_NAMES =
            setOf("hive_send", "remember", "recall", "notify", "ask_siblings")

        @Volatile
        private var INSTANCE: MikeAgent? = null

        /** The installed agent for this process, or null if [install] hasn't run. */
        fun get(): MikeAgent? = INSTANCE

        /** Convenience: the installed agent, throwing a clear error if not installed. */
        fun require(): MikeAgent =
            INSTANCE ?: error("MikeAgent.install(...) has not been called")

        /**
         * Install the shared runtime for this app. Call once from `Application.onCreate`
         * or the first activity. Does the §0 hive self-registration (network → runs on a
         * background thread you provide; here we do it inline, so call off the main thread
         * or accept that cred may be null until the next install on a background thread).
         *
         * @param context   any context; the application context is retained.
         * @param config    daemon URL + token, user name, siblings.
         * @param soul      this app's persona + goals.
         * @param appSkills the app's own skills; the four UNIVERSAL skills are added here.
         * @return the installed [MikeAgent].
         */
        @Synchronized
        fun install(
            context: Context,
            config: MikeAgentConfig,
            soul: Soul,
            appSkills: List<Skill>,
        ): MikeAgent {
            INSTANCE?.let { return it }
            val app = context.applicationContext
            val soulStore = SoulStore(app, soul)
            val hiveLog = HiveLog(app)

            // §0 self-register with the daemon (best-effort; may be null if offline).
            val loopbackClient: OkHttpClient = loopbackTrustingClientPublic(config.daemonBaseUrl)
            val cred: HiveCred? = HiveIdentity(soul.appName, config.daemonBaseUrl)
                .ensure(app, loopbackClient)

            val hiveSocket = cred?.let { HiveSocket(it, hiveLog = hiveLog) }

            // Wire the UNIVERSAL skills to real behaviour, then add the app's.
            val universal = SkillRegistry.universal(
                hiveSend = { to, type, body ->
                    hiveSocket?.send(to, type, body) ?: "hive unavailable (not registered)"
                },
                remember = { note ->
                    soulStore.remember(note, tags = "explicit")
                    "remembered"
                },
                recall = { query ->
                    val notes = soulStore.recall(query)
                    if (notes.isEmpty()) "nothing found for '$query'"
                    else notes.joinToString("\n") { "- ${it.text}" }
                },
                notify = { text -> Notifier.alert(app, text) },
                siblings = config.siblings,
                selfName = soul.agentName,
            )
            val registry = SkillRegistry(universal).register(appSkills)

            val agent = MikeAgent(app, config, soulStore, registry, cred, hiveSocket, hiveLog)
            INSTANCE = agent
            return agent
        }
    }
}
