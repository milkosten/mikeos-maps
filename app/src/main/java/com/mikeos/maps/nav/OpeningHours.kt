package com.mikeos.maps.nav

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A pragmatic evaluator for the OSM `opening_hours` string — enough to answer "open now?" AND lay the
 * week out day-by-day for the common business patterns ("Mo-Sa 08:30-20:00; Su 08:30-12:45", "24/7",
 * "Mo-Fr 09:00-12:00,14:00-17:00", slash separators, day-lists like "Tu-We,Fr …"). Anything exotic
 * (month ranges, sunrise…) → [parse] returns null and the UI falls back to the raw string.
 *
 * Times are evaluated in **Europe/Paris** (CET/CEST, DST-safe) — the roads MikeMaps drives. Mirrors the
 * web client's parser so the app and maps.osmike.com agree.
 */
object OpeningHours {

    enum class State { OPEN, CLOSESOON, LUNCH, OPENSOON, CLOSED, UNKNOWN }

    /** [state] drives the pill colour; [label] is the human phrase ("Open · closes 20:00"). */
    data class Status(val state: State, val label: String)

    /** A parsed week: [days] index 0=Mon … 6=Sun, each a list of `[startMin, endMin)` ranges. */
    data class Week(val days: List<List<IntRange>>, val always: Boolean)

    val DAY_LABEL = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val DAY_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val DOW = mapOf("mo" to 0, "tu" to 1, "we" to 2, "th" to 3, "fr" to 4, "sa" to 5, "su" to 6)
    private val PARIS = ZoneId.of("Europe/Paris")
    private val TIME_RE = Regex("(\\d{1,2}):(\\d{2})\\s*[-–]\\s*(\\d{1,2}):(\\d{2})")
    private val YEAR_RE = Regex("^\\d{4}\\b")
    private val DATE_RE = Regex("^\\d{1,2}\\s+[a-z]{3}", RegexOption.IGNORE_CASE)
    private val T247_RE = Regex("24\\s*/\\s*7")

    fun parse(spec: String?): Week? {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (T247_RE.containsMatchIn(s) || s.contains("00:00-24:00")) return Week(List(7) { listOf(0 until 1440) }, true)
        val days = MutableList(7) { mutableListOf<IntRange>() }
        var any = false
        for (ruleRaw in s.split(";")) {
            val rule = ruleRaw.trim()
            if (rule.isEmpty()) continue
            val low = rule.lowercase()
            if (low.startsWith("ph") || low.startsWith("sh")) continue          // public/school holidays
            if (YEAR_RE.containsMatchIn(rule) || DATE_RE.containsMatchIn(rule)) continue  // date-scoped rule
            val firstDigit = rule.indexOfFirst { it.isDigit() }
            val dayPart: String
            val timePart: String
            if (firstDigit == -1) {
                val oi = if (low.contains("off")) low.indexOf("off") else low.indexOf("closed")
                if (oi < 0) continue
                dayPart = rule.substring(0, oi); timePart = "off"
            } else {
                dayPart = rule.substring(0, firstDigit); timePart = rule.substring(firstDigit)
            }
            val dayIdx = parseDays(dayPart.trim())
            if (dayIdx.isEmpty()) continue
            val tpl = timePart.trim().lowercase()
            if (timePart == "off" || tpl.startsWith("off") || tpl.startsWith("closed")) {
                for (d in dayIdx) days[d].clear()
                any = true
                continue
            }
            val intervals = TIME_RE.findAll(timePart.replace("/", ",")).map {
                val st = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
                var en = it.groupValues[3].toInt() * 60 + it.groupValues[4].toInt()
                if (en <= st) en = 1440   // crosses midnight → clamp to end of day (rare)
                st until en
            }.toList()
            if (intervals.isEmpty()) continue
            for (d in dayIdx) { days[d].addAll(intervals); any = true }
        }
        if (!any) return null
        return Week(days.map { it.sortedBy { r -> r.first } }, false)
    }

    private fun parseDays(dp: String): List<Int> {
        if (dp.isBlank()) return (0..6).toList()
        val out = mutableListOf<Int>()
        for (tokRaw in dp.split(",")) {
            val tok = tokRaw.trim().lowercase()
            if (tok.isEmpty()) continue
            if (tok.contains("-")) {
                val ab = tok.split("-")
                val a: Int? = ab.getOrNull(0)?.trim()?.takeLast(2)?.let { DOW[it] }
                val b: Int? = ab.getOrNull(1)?.trim()?.take(2)?.let { DOW[it] }
                if (a != null && b != null) {
                    var i: Int = a
                    var guard = 0
                    while (true) { out.add(i); if (i == b) break; i = (i + 1) % 7; if (++guard > 8) break }
                }
            } else {
                DOW[tok.takeLast(2)]?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    /** Live status for a parsed [week] at [now] (defaults to Paris time). */
    fun status(week: Week?, now: LocalDateTime = LocalDateTime.now(PARIS)): Status {
        if (week == null) return Status(State.UNKNOWN, "")
        if (week.always) return Status(State.OPEN, "Open 24 hours")
        val dayIdx = now.dayOfWeek.value - 1
        val nowMin = now.hour * 60 + now.minute
        val today = week.days[dayIdx]
        for (r in today) if (nowMin in r) {
            val end = r.last + 1
            return if (end - nowMin <= 30) Status(State.CLOSESOON, "Closes soon · ${fmt(end)}")
            else Status(State.OPEN, "Open · closes ${fmt(end)}")
        }
        val later = today.filter { it.first > nowMin }.minByOrNull { it.first }
        if (later != null) {
            val opensAt = later.first
            val earlierEnded = today.any { it.last + 1 <= nowMin }
            if (earlierEnded && opensAt in (11 * 60)..(15 * 60)) return Status(State.LUNCH, "Closed for lunch · opens ${fmt(opensAt)}")
            if (opensAt - nowMin <= 60) return Status(State.OPENSOON, "Opens soon · ${fmt(opensAt)}")
            return Status(State.CLOSED, "Closed · opens ${fmt(opensAt)}")
        }
        for (k in 1..7) {
            val di = (dayIdx + k) % 7
            if (week.days[di].isNotEmpty()) {
                val whenTxt = if (k == 1) "tomorrow" else DAY_LABEL[di]
                return Status(State.CLOSED, "Closed · opens $whenTxt ${fmt(week.days[di].first().first)}")
            }
        }
        return Status(State.CLOSED, "Closed")
    }

    /** Convenience: parse [spec] then evaluate — for callers that only want the status line. */
    fun status(spec: String?, now: LocalDateTime = LocalDateTime.now(PARIS)): Status = status(parse(spec), now)

    /** Human hours for day [i] (0=Mon): "08:30–20:00, 14:00–17:00" or "Closed" (or "24 hours"). */
    fun dayLabel(week: Week, i: Int): String = when {
        week.always -> "24 hours"
        week.days[i].isEmpty() -> "Closed"
        else -> week.days[i].joinToString(", ") { "${fmt(it.first)}–${fmtEnd(it.last + 1)}" }
    }

    fun todayIndex(now: LocalDateTime = LocalDateTime.now(PARIS)): Int = now.dayOfWeek.value - 1

    private fun fmt(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)
    private fun fmtEnd(min: Int): String = if (min >= 1440) "00:00" else fmt(min)
}
