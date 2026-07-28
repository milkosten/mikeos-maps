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

    /** Distance (km) to the nearest vertex of [route]. */
    fun nearestKm(route: List<PolylineCodec.LatLon>, curLat: Double, curLon: Double): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (p in route) {
            val d = haversineKm(curLat, curLon, p.lat, p.lon)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Cross-track distance (meters) from (curLat,curLon) to the route *line* — the perpendicular
     * distance to the nearest SEGMENT, not just the nearest vertex. This is what off-route detection
     * must use: on a long straight stretch the polyline vertices can be far apart, so vertex distance
     * falsely reads as "off route" even when you're driving right on the road.
     */
    fun distanceToRouteM(route: List<PolylineCodec.LatLon>, curLat: Double, curLon: Double): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) return haversineKm(curLat, curLon, route[0].lat, route[0].lon) * 1000.0
        // Local equirectangular projection with the point at the origin (accurate at these scales).
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(curLat))
        var ax = (route[0].lon - curLon) * mPerDegLon
        var ay = (route[0].lat - curLat) * mPerDegLat
        var best = Double.MAX_VALUE
        for (i in 1 until route.size) {
            val bx = (route[i].lon - curLon) * mPerDegLon
            val by = (route[i].lat - curLat) * mPerDegLat
            val d = originToSegment(ax, ay, bx, by)
            if (d < best) best = d
            ax = bx; ay = by
        }
        return best
    }

    /** Distance from the origin (0,0) to the segment (ax,ay)–(bx,by), same units as the inputs. */
    private fun originToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else (((-ax) * dx + (-ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /** A point snapped onto the route line: its location, the route's forward bearing there, and how
     * far ([offsetM]) the raw fix was from the line. */
    data class Snap(val lat: Double, val lon: Double, val bearingDeg: Double, val offsetM: Double)

    /**
     * Snap a raw GPS fix onto the route LINE — the nearest point on the nearest segment, plus that
     * segment's forward bearing (direction of travel there). GPS is ±5-10 m noisy, so during driving
     * the puck should ride the blue line pointing forward, not float beside it. Returns null if the
     * route is too short; callers should ignore the snap when [Snap.offsetM] is large (genuinely
     * off-route → let the reroute logic handle it) and fall back to the raw fix.
     */
    fun snapToRoute(route: List<PolylineCodec.LatLon>, curLat: Double, curLon: Double): Snap? {
        if (route.size < 2) return null
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(curLat))
        var ax = (route[0].lon - curLon) * mPerDegLon
        var ay = (route[0].lat - curLat) * mPerDegLat
        var best = Double.MAX_VALUE
        var bestLat = curLat; var bestLon = curLon; var bestBearing = 0.0
        for (i in 1 until route.size) {
            val bx = (route[i].lon - curLon) * mPerDegLon
            val by = (route[i].lat - curLat) * mPerDegLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else (((-ax) * dx + (-ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            if (d < best) {
                best = d
                bestLon = curLon + cx / mPerDegLon
                bestLat = curLat + cy / mPerDegLat
                bestBearing = bearingDeg(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
            }
            ax = bx; ay = by
        }
        return Snap(bestLat, bestLon, bestBearing, best)
    }

    /** The portion of [route] from the vertex nearest the current position onward (the road ahead). */
    fun routeAhead(route: List<PolylineCodec.LatLon>, curLat: Double, curLon: Double): List<PolylineCodec.LatLon> {
        if (route.size < 2) return route
        var nearest = 0; var best = Double.MAX_VALUE
        for (i in route.indices) {
            val d = haversineKm(curLat, curLon, route[i].lat, route[i].lon)
            if (d < best) { best = d; nearest = i }
        }
        return route.subList(nearest, route.size)
    }

    /** Distance (m) ALONG [route] from its start to the point on it nearest to (poiLat,poiLon) — used
     * to order along-route POIs by drive-past order. Vertex-granularity, plenty for ordering. */
    fun alongRouteM(route: List<PolylineCodec.LatLon>, poiLat: Double, poiLon: Double): Int {
        if (route.size < 2) return 0
        var nearest = 0; var best = Double.MAX_VALUE
        for (i in route.indices) {
            val d = haversineKm(poiLat, poiLon, route[i].lat, route[i].lon)
            if (d < best) { best = d; nearest = i }
        }
        var m = 0.0
        for (i in 0 until nearest) m += haversineKm(route[i].lat, route[i].lon, route[i + 1].lat, route[i + 1].lon) * 1000.0
        return m.toInt()
    }

    /** Initial bearing (degrees clockwise from north, 0-360) from A to B. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 8-point compass abbreviation for a bearing in degrees (0 = North). */
    fun compass(bearingDeg: Double): String = COMPASS[(((bearingDeg + 22.5) % 360.0) / 45.0).toInt() % 8]

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
 * Speed-adaptive follow zoom while navigating (like Google Maps): slow → zoomed in, fast → zoomed
 * out, so you always see roughly the right look-ahead. Mike's feel: ~100 m radius at 10 km/h,
 * ~500 m at 30, ~1 km at 70. We turn a speed into a desired look-ahead radius, then into a MapLibre
 * zoom given the actual viewport height + latitude (Web-Mercator, 512-px tiles).
 */
object NavCamera {

    // (speed km/h → look-ahead radius m). Linearly interpolated; honours Mike's anchor points.
    private val ANCHORS = listOf(
        0.0 to 90.0,
        10.0 to 100.0,
        30.0 to 500.0,
        70.0 to 1000.0,
        120.0 to 1600.0,
    )

    fun radiusForSpeed(kmh: Double): Double {
        val s = kmh.coerceAtLeast(0.0)
        for (i in 0 until ANCHORS.size - 1) {
            val (s0, r0) = ANCHORS[i]
            val (s1, r1) = ANCHORS[i + 1]
            if (s <= s1) {
                val t = if (s1 > s0) (s - s0) / (s1 - s0) else 0.0
                return r0 + t * (r1 - r0)
            }
        }
        return ANCHORS.last().second
    }

    /** MapLibre zoom that shows [radiusM] from map centre to the top edge of an [heightPx]-tall map. */
    fun zoomForRadius(radiusM: Double, lat: Double, heightPx: Double): Double {
        val h = if (heightPx > 100) heightPx else 1920.0
        val r = radiusM.coerceAtLeast(30.0)
        // metersPerPixel = 156543.03392 * cos(lat) / 2^(zoom+1); radius = metersPerPixel * (h/2).
        val z = Math.log(156543.03392 * Math.cos(Math.toRadians(lat)) * h / (2.0 * r)) / Math.log(2.0) - 1.0
        return z.coerceIn(12.5, 18.5)
    }

    fun zoomForSpeed(kmh: Double, lat: Double, heightPx: Double): Double =
        zoomForRadius(radiusForSpeed(kmh), lat, heightPx)
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
