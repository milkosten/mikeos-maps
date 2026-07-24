package com.mikeos.maps.net

import android.util.Log
import com.mikeos.core.net.loopbackTrustingClientPublic
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the ONE shared location fix from the on-device daemon — MikeMaps NEVER runs its own
 * GPS (APP-ANATOMY §3a). `GET https://127.0.0.1:7743/api/location` is a loopback, auth-exempt
 * endpoint served over the daemon's self-signed TLS, so we use the core loopback-trusting
 * client (scoped to 127.0.0.1 only).
 *
 * The daemon's fix may or may not carry a speed. When it doesn't, [derivedSpeedKmh] computes
 * speed_kmh from the previous fix (haversine distance / dt).
 */
object DaemonLocation {

    private const val TAG = "DaemonLocation"

    data class Fix(val lat: Double, val lon: Double, val speedKmh: Double?, val ts: Long)

    private val client: OkHttpClient =
        loopbackTrustingClientPublic(BuildConfig.DAEMON_BASE_URL).newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    // Last fix seen, so we can derive speed across consecutive reads when the daemon omits it.
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var lastTs: Long = 0L

    /** Read the current fix from the daemon. Null if unreachable / no fix. Never throws. */
    suspend fun current(): Fix? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${BuildConfig.DAEMON_BASE_URL}/api/location")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "location HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                // Tolerate a few common shapes: {lat,lon,...} or {location:{lat,lon}}.
                val loc = o.optJSONObject("location") ?: o
                val lat = loc.optDouble("lat", Double.NaN)
                val lon = loc.optDouble("lon", loc.optDouble("lng", Double.NaN))
                if (lat.isNaN() || lon.isNaN()) {
                    Log.w(TAG, "location 200 but no lat/lon: $raw")
                    return@withContext null
                }
                // Daemon may give speed in m/s ("speed") or km/h ("speed_kmh"); prefer explicit km/h.
                val speedKmh: Double? = when {
                    loc.has("speed_kmh") && !loc.isNull("speed_kmh") -> loc.optDouble("speed_kmh").takeUnless { it.isNaN() }
                    loc.has("speed") && !loc.isNull("speed") -> loc.optDouble("speed").takeUnless { it.isNaN() }?.let { it * 3.6 }
                    else -> null
                }
                val now = System.currentTimeMillis()
                val derived = speedKmh ?: derivedSpeedKmh(lat, lon, now)
                lastLat = lat; lastLon = lon; lastTs = now
                Fix(lat, lon, derived, now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "location failed: ${e.message}")
            null
        }
    }

    /** Speed in km/h from the previous fix (haversine / dt), or null if no prior fix / dt too small. */
    private fun derivedSpeedKmh(lat: Double, lon: Double, nowMs: Long): Double? {
        val pLat = lastLat ?: return null
        val pLon = lastLon ?: return null
        val dtSec = (nowMs - lastTs) / 1000.0
        if (dtSec < 1.0) return null
        val meters = haversineMeters(pLat, pLon, lat, lon)
        val kmh = (meters / dtSec) * 3.6
        // Clamp absurd jumps (GPS teleport) to avoid poisoning the congestion model.
        return kmh.coerceIn(0.0, 250.0)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
