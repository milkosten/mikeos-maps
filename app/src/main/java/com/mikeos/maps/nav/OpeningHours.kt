package com.mikeos.maps.nav

import java.time.LocalDateTime

/**
 * A pragmatic evaluator for the OSM `opening_hours` string — enough to answer "open now?" for the
 * common business patterns ("Mo-Sa 08:30-20:00; Su 08:30-12:45", "24/7", multiple time ranges,
 * over-midnight). Anything exotic (public holidays, month ranges, sunrise…) returns UNKNOWN, and the
 * UI just shows the raw hours string.
 */
object OpeningHours {

    /** open = true/false/null(unknown); [label] a short human phrase ("Open · closes 20:00"). */
    data class Status(val open: Boolean?, val label: String)

    private val DOW = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val TIMES = Regex("([0-2]?\\d:[0-5]\\d)\\s*-\\s*([0-2]?\\d:[0-5]\\d)(\\s*,\\s*([0-2]?\\d:[0-5]\\d)\\s*-\\s*([0-2]?\\d:[0-5]\\d))*")

    fun status(spec: String?, now: LocalDateTime = LocalDateTime.now()): Status {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return Status(null, "")
        if (s.replace(" ", "") == "24/7") return Status(true, "Open 24 hours")
        return try {
            val todayIdx = now.dayOfWeek.value - 1   // Mon = 0
            val nowMin = now.hour * 60 + now.minute
            var appliesToday = false
            var openNow = false
            var closesAt = -1
            for (ruleRaw in s.split(";")) {
                val rule = ruleRaw.trim()
                if (rule.isEmpty()) continue
                val m = TIMES.find(rule) ?: return Status(null, "")   // no time range we understand → unknown
                val daysPart = rule.substring(0, m.range.first).trim().trimEnd(',').trim()
                val days = if (daysPart.isBlank()) (0..6).toSet() else parseDays(daysPart) ?: return Status(null, "")
                if (todayIdx !in days) continue
                appliesToday = true
                for (tr in m.value.split(",")) {
                    val se = tr.split("-")
                    if (se.size != 2) return Status(null, "")
                    val start = toMin(se[0]) ?: return Status(null, "")
                    val rawEnd = toMin(se[1]) ?: return Status(null, "")
                    val end = if (rawEnd <= start) rawEnd + 1440 else rawEnd   // over-midnight
                    if (nowMin in start until end || nowMin + 1440 in start until end) {
                        openNow = true; closesAt = rawEnd
                    }
                }
            }
            when {
                openNow -> Status(true, "Open · closes ${fmt(closesAt)}")
                appliesToday -> Status(false, "Closed now")
                else -> Status(false, "Closed today")
            }
        } catch (e: Exception) {
            Status(null, "")
        }
    }

    private fun parseDays(s: String): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (partRaw in s.split(",")) {
            val p = partRaw.trim()
            if (p.isEmpty()) continue
            if ("-" in p) {
                val ab = p.split("-")
                val a = DOW.indexOf(ab.getOrNull(0)?.trim())
                val b = DOW.indexOf(ab.getOrNull(1)?.trim())
                if (a < 0 || b < 0) return null
                var i = a
                var guard = 0
                while (true) {
                    out.add(i % 7)
                    if (i % 7 == b) break
                    i++; if (++guard > 8) return null
                }
            } else {
                val i = DOW.indexOf(p)
                if (i < 0) return null
                out.add(i)
            }
        }
        return out.ifEmpty { null }
    }

    private fun toMin(hhmm: String): Int? {
        val hm = hhmm.trim().split(":")
        val h = hm.getOrNull(0)?.toIntOrNull() ?: return null
        val m = hm.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun fmt(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)
}
