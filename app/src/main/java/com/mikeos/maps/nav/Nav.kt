package com.mikeos.maps.nav

import com.mikeos.maps.net.PolylineCodec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Human formatting for the driving HUD — distance, duration, ETA clock. */
object NavFormat {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** "620 m" / "3.4 km" / "128 km". */
    fun distance(km: Double): String = when {
        km.isNaN() -> "—"
        km < 1.0 -> "${(km * 1000).roundToInt()} m"
        km < 10.0 -> "${"%.1f".format(km)} km"
        else -> "${km.roundToInt()} km"
    }

    /** minutes → "8 min" / "1 h 12 min" / "2 d 3 h". */
    fun duration(min: Double): String {
        if (min.isNaN() || min < 0) return "—"
        val total = min.roundToInt()
        val days = total / (60 * 24)
        val hours = (total % (60 * 24)) / 60
        val mins = total % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $mins min"
            else -> "$mins min"
        }
    }

    /** Clock time `now + min` → "13:32". */
    fun eta(min: Double, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (min.isNaN() || min < 0) return "—"
        val at = Instant.ofEpochMilli(nowMs + (min * 60_000L).toLong()).atZone(zone)
        return CLOCK.format(at)
    }
}

/** Geo helpers for route progress. */
object NavGeo {

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Remaining route distance (km) from the current position to the end of [route]: snap to the
     * nearest route vertex, then sum the segment lengths from there onward (plus the hop from the
     * position to that vertex). Vertex-granularity — plenty accurate for a HUD.
     */
    fun remainingKm(route: List<PolylineCodec.LatLon>, curLat: Double, curLon: Double): Double {
        if (route.size < 2) return 0.0
        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in route.indices) {
            val d = haversineKm(curLat, curLon, route[i].lat, route[i].lon)
            if (d < best) { best = d; nearest = i }
        }
        var rem = best
        for (i in nearest until route.size - 1) {
            rem += haversineKm(route[i].lat, route[i].lon, route[i + 1].lat, route[i + 1].lon)
        }
        return rem
    }
}

/**
 * Live navigation readout shown in the driving HUD, recomputed each location tick while a trip is
 * active. [remainingMin] uses the live speed when moving, else the route's planned average.
 */
data class NavInfo(
    val speedKmh: Double,
    val remainingKm: Double,
    val remainingMin: Double,
) {
    val etaClock: String get() = NavFormat.eta(remainingMin)

    companion object {
        fun compute(
            speedKmh: Double?,
            route: List<PolylineCodec.LatLon>,
            curLat: Double,
            curLon: Double,
            plannedKm: Double,
            plannedEtaMin: Double,
        ): NavInfo {
            val speed = speedKmh ?: 0.0
            val remKm = NavGeo.remainingKm(route, curLat, curLon)
            val plannedAvg = if (plannedEtaMin > 0.5 && plannedKm > 0) plannedKm / (plannedEtaMin / 60.0) else 40.0
            val useKmh = if (speed > 5.0) speed else plannedAvg
            val remMin = if (useKmh > 0.1) remKm / useKmh * 60.0 else Double.NaN
            return NavInfo(speed, remKm, remMin)
        }
    }
}
