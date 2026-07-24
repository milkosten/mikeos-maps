package com.mikeos.core.hive

import java.util.concurrent.ConcurrentHashMap

/**
 * What one sibling agent told the hive it can do, learned from its `capability.announce`.
 *
 * @property app      the sibling's short app name (e.g. "MikePhotos").
 * @property persona  a short line about who the sibling is.
 * @property offers   one human line describing what it offers ("searches your photos…").
 * @property skills   the sibling's app skills as "name — description" lines.
 * @property lastSeenMs epoch millis its most recent announce arrived.
 */
data class SiblingInfo(
    val app: String,
    val persona: String,
    val offers: String,
    val skills: List<String>,
    val lastSeenMs: Long,
)

/**
 * Hive collaboration protocol (Phase 2) — the LIVE capability directory.
 *
 * A thread-safe, in-memory registry of "who is online and what each offers", populated
 * from inbound `capability.announce` messages (each agent announces on connect + a
 * periodic re-announce so late joiners learn about it). [render] produces a concise block
 * injected into the SYSTEM-CONTEXT so the brain reasons over REAL, live capabilities and
 * can ask the RIGHT sibling for what it needs.
 *
 * In-memory only (rebuilt as announces re-arrive) — best-effort, never a system of record.
 */
class SiblingDirectory {

    private val map = ConcurrentHashMap<String, SiblingInfo>()

    /** Record (or refresh) what a sibling offers. Keyed by short app name. */
    fun upsert(app: String, persona: String, offers: String, skills: List<String>) {
        val key = app.substringAfterLast('/').trim()
        if (key.isBlank()) return
        map[key] = SiblingInfo(
            app = key,
            persona = persona.trim(),
            offers = offers.trim(),
            skills = skills,
            lastSeenMs = System.currentTimeMillis(),
        )
    }

    /** Short app names currently known (fresh only). */
    fun knownApps(): Set<String> = fresh().map { it.app }.toSet()

    /** True once we've heard at least one live announce. */
    fun isEmpty(): Boolean = fresh().isEmpty()

    /** Non-stale entries, dropping any not seen within [STALE_MS]. */
    private fun fresh(): List<SiblingInfo> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        // Opportunistically evict stale entries so the directory self-heals.
        val stale = map.values.filter { it.lastSeenMs < cutoff }
        stale.forEach { map.remove(it.app, it) }
        return map.values.filter { it.lastSeenMs >= cutoff }.sortedBy { it.app }
    }

    /**
     * A concise "who's online and what they offer" block for the prompt, e.g.:
     *   - MikePhotos — searches your photos, answers questions about them
     *   - MikeShopping — shopping lists + prices
     * Returns "" when nothing is known (the caller falls back to the static config list).
     */
    fun render(): String {
        val entries = fresh()
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { s ->
            val offer = s.offers.ifBlank { s.persona.ifBlank { "(available)" } }
            "- ${s.app} — ${offer.take(160)}"
        }
    }

    companion object {
        /** Drop a sibling we haven't heard an announce from in this long. */
        private const val STALE_MS = 30L * 60L * 1000L // 30 minutes
    }
}
