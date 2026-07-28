package com.mikeos.maps.net

import android.util.Log
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.nav.NavGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The posted speed limit (km/h) for the road you're on, from OSM `maxspeed` via our self-hosted
 * Overpass (fast tag/around query, ~0.3 s).
 *
 * INTERIM data source: it snaps to the nearest *tagged* road, preferring one whose direction matches
 * your travel bearing — so a 70 corniche isn't confused with a parallel 30 side-street. It only
 * returns EXPLICIT `maxspeed` (no guessing) → the badge shows when we're confident, hides otherwise.
 *
 * TODO (when route.osmike.com is live): replace the Overpass query with Valhalla map-matching
 * (`/locate` or `/trace_attributes`), which snaps to the road network properly AND fills implicit
 * defaults by road class + country. This object is the single seam to swap.
 */
object SpeedLimit {

    private const val TAG = "SpeedLimit"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent)"
    private val ENDPOINT = "${BuildConfig.OVERPASS_SELF_URL}/api/interpreter"
    private const val RADIUS_M = 50            // the road you're actually on
    private const val MISALIGN_PENALTY_M = 40  // push down roads whose direction doesn't match travel

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Current road's posted limit in km/h, or null if unknown/untagged. [bearingDeg] disambiguates parallel roads. */
    suspend fun at(lat: Double, lon: Double, bearingDeg: Double?): Int? = withContext(Dispatchers.IO) {
        val ql = "[out:json][timeout:10];way[highway][maxspeed](around:$RADIUS_M,$lat,$lon);out tags geom;"
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", UA)
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .post(FormBody.Builder().add("data", ql).build())
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "overpass HTTP ${resp.code}"); return@withContext null }
                val els = runCatching { JSONObject(resp.body?.string().orEmpty()).optJSONArray("elements") }
                    .getOrNull() ?: return@withContext null
                var best: Int? = null
                var bestScore = Double.MAX_VALUE
                for (i in 0 until els.length()) {
                    val e = els.optJSONObject(i) ?: continue
                    val speed = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed")) ?: continue
                    val geom = e.optJSONArray("geometry") ?: continue
                    var minD = Double.MAX_VALUE
                    var segBearing = 0.0
                    for (j in 0 until geom.length() - 1) {
                        val a = geom.optJSONObject(j) ?: continue
                        val b = geom.optJSONObject(j + 1) ?: continue
                        val d = NavGeo.haversineKm(lat, lon, a.optDouble("lat"), a.optDouble("lon")) * 1000
                        if (d < minD) {
                            minD = d
                            segBearing = NavGeo.bearingDeg(a.optDouble("lat"), a.optDouble("lon"), b.optDouble("lat"), b.optDouble("lon"))
                        }
                    }
                    var score = minD
                    if (bearingDeg != null) {
                        val diff = angleDiff(bearingDeg, segBearing)
                        val misalign = minOf(diff, 180 - diff)   // 0 = parallel (either direction), 90 = perpendicular
                        if (misalign > 55) score += MISALIGN_PENALTY_M   // likely a cross street
                    }
                    if (score < bestScore) { bestScore = score; best = speed }
                }
                best
            }
        } catch (e: Exception) {
            Log.w(TAG, "speed-limit lookup failed: ${e.message}"); null
        }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    /** OSM maxspeed → km/h. Bare numbers, "50 mph", and the common implicit FR:* tags. */
    private fun parseMaxspeed(v: String?): Int? {
        val s = v?.trim()?.lowercase()?.takeUnless { it.isEmpty() } ?: return null
        return when {
            s.toIntOrNull() != null -> s.toInt()
            s.endsWith("mph") -> s.removeSuffix("mph").trim().toIntOrNull()?.let { (it * 1.60934).toInt() }
            s.contains("urban") -> 50
            s.contains("rural") -> 80
            s.contains("motorway") -> 130
            s.contains("living_street") || s.contains("walk") -> 20
            else -> null   // "none" / "signals" / unparseable → don't guess
        }
    }
}
