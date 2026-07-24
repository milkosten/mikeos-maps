package com.mikeos.maps.nav

import com.mikeos.maps.net.TripsCloudClient.RouteStep

/** The kind of maneuver — drives which arrow the banner shows. */
enum class ManeuverKind {
    DEPART, ARRIVE, STRAIGHT,
    LEFT, SLIGHT_LEFT, SHARP_LEFT,
    RIGHT, SLIGHT_RIGHT, SHARP_RIGHT,
    UTURN, ROUNDABOUT, MERGE, FORK,
}

/** A single live turn instruction: what to do, how far ahead, and which step it is. */
data class Guidance(
    val instruction: String,
    val kind: ManeuverKind,
    val distanceM: Double,
    val stepIndex: Int,
) {
    /** A short spoken form, e.g. "In 300 meters, turn left onto Rue de France". */
    fun spoken(): String {
        val d = distanceM
        val lead = when {
            kind == ManeuverKind.ARRIVE -> ""
            d >= 900 -> "In ${(d / 100).toInt() * 100 / 1000.0} kilometers, "
            d >= 60 -> "In ${(d / 50).toInt() * 50} meters, "
            else -> ""
        }
        return lead + instruction
    }
}

/** Turns OSRM [RouteStep]s into human maneuvers + tracks which one is next. */
object NavGuidance {

    fun kindOf(step: RouteStep): ManeuverKind {
        val t = step.type?.lowercase().orEmpty()
        val m = step.modifier?.lowercase().orEmpty()
        return when {
            t == "arrive" -> ManeuverKind.ARRIVE
            t == "depart" -> ManeuverKind.DEPART
            t.contains("roundabout") || t.contains("rotary") -> ManeuverKind.ROUNDABOUT
            t == "merge" -> ManeuverKind.MERGE
            t == "fork" -> ManeuverKind.FORK
            m.contains("uturn") -> ManeuverKind.UTURN
            m == "sharp left" -> ManeuverKind.SHARP_LEFT
            m == "slight left" -> ManeuverKind.SLIGHT_LEFT
            m == "left" -> ManeuverKind.LEFT
            m == "sharp right" -> ManeuverKind.SHARP_RIGHT
            m == "slight right" -> ManeuverKind.SLIGHT_RIGHT
            m == "right" -> ManeuverKind.RIGHT
            else -> ManeuverKind.STRAIGHT
        }
    }

    fun instruction(step: RouteStep): String {
        val t = step.type?.lowercase().orEmpty()
        val onto = if (step.name.isNotBlank()) " onto ${step.name}" else ""
        return when (kindOf(step)) {
            ManeuverKind.ARRIVE -> "Arrive at destination"
            ManeuverKind.DEPART -> if (step.name.isNotBlank()) "Head onto ${step.name}" else "Start driving"
            ManeuverKind.ROUNDABOUT -> "Take the roundabout" + if (step.name.isNotBlank()) ", exit onto ${step.name}" else ""
            ManeuverKind.MERGE -> "Merge$onto"
            ManeuverKind.FORK -> "Keep ${step.modifier ?: "ahead"}$onto"
            ManeuverKind.UTURN -> "Make a U-turn"
            ManeuverKind.SHARP_LEFT -> "Sharp left$onto"
            ManeuverKind.SLIGHT_LEFT -> "Slight left$onto"
            ManeuverKind.LEFT -> "Turn left$onto"
            ManeuverKind.SHARP_RIGHT -> "Sharp right$onto"
            ManeuverKind.SLIGHT_RIGHT -> "Slight right$onto"
            ManeuverKind.RIGHT -> "Turn right$onto"
            ManeuverKind.STRAIGHT ->
                if (t == "continue" || t == "new name") {
                    if (step.name.isNotBlank()) "Continue on ${step.name}" else "Continue straight"
                } else "Continue straight$onto"
        }
    }

    /** Distance (meters) from a point to a step's maneuver location. */
    fun distanceM(step: RouteStep, lat: Double, lon: Double): Double =
        NavGeo.haversineKm(lat, lon, step.lat, step.lon) * 1000.0

    /**
     * Given the last-known step [fromIndex] and the current position, advance past any maneuvers
     * we've reached and return the [Guidance] for the next one, plus the (possibly advanced) index.
     */
    fun next(steps: List<RouteStep>, fromIndex: Int, lat: Double, lon: Double): Pair<Guidance, Int>? {
        if (steps.isEmpty()) return null
        var idx = fromIndex.coerceIn(0, steps.lastIndex)
        // Advance while we're on top of the current maneuver, or the next one is clearly closer.
        while (idx < steps.lastIndex) {
            val d = distanceM(steps[idx], lat, lon)
            val dNext = distanceM(steps[idx + 1], lat, lon)
            if (d < ADVANCE_M || dNext < d - 5.0) idx++ else break
        }
        val step = steps[idx]
        return Guidance(instruction(step), kindOf(step), distanceM(step, lat, lon), idx) to idx
    }

    private const val ADVANCE_M = 20.0

    /**
     * OSRM's planned time (minutes) for the LAST [remKm] of the route — walks the steps from the
     * end summing their durations until [remKm] is covered (partial on the boundary step). This is
     * road-type aware (highway steps are fast, city steps slow), unlike a flat remainingKm/speed.
     * Null if there are no usable step durations (caller falls back to the speed-based estimate).
     */
    fun plannedRemainingMin(steps: List<RouteStep>, remKm: Double): Double? {
        if (steps.isEmpty()) return null
        if (steps.sumOf { it.durationS } <= 0.0) return null
        val remM = (remKm * 1000.0).coerceAtLeast(0.0)
        var accM = 0.0
        var accS = 0.0
        for (st in steps.asReversed()) {
            if (accM >= remM) break
            val stepM = st.distanceM
            val need = remM - accM
            if (stepM <= need || stepM <= 0.0) {
                accM += stepM
                accS += st.durationS
            } else {
                accS += st.durationS * (need / stepM)
                accM += need
            }
        }
        return accS / 60.0
    }
}
