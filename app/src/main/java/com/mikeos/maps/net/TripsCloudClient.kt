package com.mikeos.maps.net

import android.util.Log
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Talks to **mikeos-trips-cloud** — Mike's per-user journeys, their speed/GPS trails, and
 * a congestion model aggregated from the trails. MikeMaps routes A→B, records the drive as a
 * stream of speed+loc samples, and reads the congestion profile back.
 *
 * Auth: every trip call carries `X-API-KEY: <hive agent key>` (from
 * [com.mikeos.core.hive.HiveIdentity] / the installed [com.mikeos.core.agent.MikeAgent]).
 * `POST /api/route` is KEYLESS (pure OSRM wrapper). The cloud resolves the key to a `user_id`
 * via mikeoscomputers and scopes everything per user.
 *
 * TLS: Railway has a valid public cert, so a STANDARD OkHttpClient is used — with **DoH** for
 * this ROM's flaky system DNS — never the loopback trust-all client.
 *
 * House rules honoured:
 *  - sample `ts` is ALWAYS an ISO-8601 string (`Instant.ofEpochMilli(x).toString()`), never an
 *    epoch-ms Long — a Long makes FastAPI clouds return 200 while persisting 0 rows;
 *  - HTTP 200 is never trusted alone — createTrip verifies a real `trip_id`, postSamples verifies
 *    `stored > 0`, endTrip verifies a `trip_id`.
 *
 * API (per the trips-cloud spec, confirmed live):
 *   POST /api/route                  {from:{lat,lon},to:{lat,lon}} -> {ok,polyline,km,eta_min}   KEYLESS
 *   POST /api/trips                  {dest_name,dest_lat,dest_lon,origin_lat,origin_lon,polyline,km,eta_min,mode,device_id} -> {trip_id,started_at}
 *   POST /api/trips/{id}/samples     {samples:[{lat,lon,speed_kmh,ts}]} -> {stored}
 *   POST /api/trips/{id}/end         {avg_kmh?} -> {trip_id,duration_min,sample_count,avg_kmh}
 *   GET  /api/trips?limit=20         -> {trips:[...]}
 *   GET  /api/trips/{id}             -> {trip:{...}} (with sample_count)
 *   GET  /api/congestion?lat=&lon=&radius_m=&hour= -> {avg_kmh,sample_count,by_hour}
 */
class TripsCloudClient(
    private val baseUrl: String = BuildConfig.TRIPS_CLOUD_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)                          // phone's system DNS is flaky — resolve via Cloudflare
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** A route from OSRM: an encoded polyline (precision 5), distance km, ETA minutes, + steps. */
    data class Route(
        val polyline: String,
        val km: Double,
        val etaMin: Double,
        val steps: List<RouteStep> = emptyList(),
    )

    /** One OSRM maneuver step: what to do (type + modifier) and where (lat/lon), onto [name]. */
    data class RouteStep(
        val type: String,        // depart, turn, continue, merge, roundabout, arrive, …
        val modifier: String?,   // left, right, slight left, sharp right, straight, uturn, …
        val name: String,        // the road you're on after the maneuver
        val distanceM: Double,   // length of this step
        val durationS: Double,   // OSRM planned duration of this step (road-type aware) — for ETA
        val lat: Double,
        val lon: Double,
    )

    /** A single speed+loc sample recorded during a drive (+ the live ETA estimate, for analysis). */
    data class Sample(
        val lat: Double,
        val lon: Double,
        val speedKmh: Double,
        val tsMillis: Long,
        val etaMin: Double? = null,
        val remainingKm: Double? = null,
    )

    /** A trip row (from the history list / detail). */
    data class Trip(
        val tripId: String,
        val destName: String?,
        val km: Double?,
        val etaMin: Double?,
        val avgKmh: Double?,
        val durationMin: Double?,
        val sampleCount: Int?,
        val mode: String?,
        val startedAt: String?,
        val endedAt: String?,
    )

    /** The summary returned by end_trip. */
    data class TripSummary(val tripId: String, val durationMin: Double, val sampleCount: Int, val avgKmh: Double?)

    /** A congestion profile near a point. */
    data class Congestion(val avgKmh: Double?, val sampleCount: Int, val byHour: Map<Int, Double>)

    private fun keyed(apiKey: String, path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")

    private fun unkeyed(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")

    // ---- ROUTE (keyless) -------------------------------------------------------------------

    /**
     * Compute a driving route A→B via OSRM -> `POST /api/route`. KEYLESS. Returns null on
     * failure. Never throws.
     */
    suspend fun route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): Route? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("from", JSONObject().put("lat", fromLat).put("lon", fromLon))
            .put("to", JSONObject().put("lat", toLat).put("lon", toLon))
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(unkeyed("/api/route").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "route HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val poly = o.optString("polyline").takeUnless { it.isBlank() }
                if (!o.optBoolean("ok", true) || poly == null) {
                    Log.w(TAG, "route 200 but no polyline: $raw")
                    return@withContext null
                }
                val stepsArr = o.optJSONArray("steps")
                val steps = if (stepsArr != null) {
                    (0 until stepsArr.length()).mapNotNull { i ->
                        val s = stepsArr.optJSONObject(i) ?: return@mapNotNull null
                        val lat = s.optDouble("lat", Double.NaN)
                        val lon = s.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                        RouteStep(
                            type = s.optString("type"),
                            modifier = s.optString("modifier").takeUnless { it.isBlank() || it == "null" },
                            name = s.optString("name"),
                            distanceM = s.optDouble("distance_m", 0.0),
                            durationS = s.optDouble("duration_s", 0.0),
                            lat = lat, lon = lon,
                        )
                    }
                } else emptyList()
                Route(poly, o.optDouble("km", 0.0), o.optDouble("eta_min", 0.0), steps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "route failed: ${e.message}")
            null
        }
    }

    // ---- TRIP LIFECYCLE (keyed) ------------------------------------------------------------

    /**
     * Create a trip -> `POST /api/trips`. Returns the confirmed `trip_id` (verified in the
     * body — never trust 200 alone) or null. Never throws.
     */
    suspend fun createTrip(
        apiKey: String,
        destName: String,
        destLat: Double, destLon: Double,
        originLat: Double, originLon: Double,
        polyline: String,
        km: Double, etaMin: Double,
        deviceId: String?,
        mode: String = "driving",
    ): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("dest_name", destName)
            .put("dest_lat", destLat)
            .put("dest_lon", destLon)
            .put("origin_lat", originLat)
            .put("origin_lon", originLon)
            .put("polyline", polyline)
            .put("km", km)
            .put("eta_min", etaMin)
            .put("mode", mode)
            .apply { if (!deviceId.isNullOrBlank()) put("device_id", deviceId) }
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(keyed(apiKey, "/api/trips").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "createTrip HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull()
                val tripId = o?.optString("trip_id").takeUnless { it.isNullOrBlank() }
                if (tripId == null) Log.w(TAG, "createTrip 200 but no trip_id: $raw")
                tripId
            }
        } catch (e: Exception) {
            Log.w(TAG, "createTrip failed: ${e.message}")
            null
        }
    }

    /**
     * Post speed+loc samples -> `POST /api/trips/{id}/samples`. Each `ts` goes out as an
     * ISO-8601 string (NEVER an epoch Long — the #1 silent-drop bug). Returns the confirmed
     * `stored` count (0 means nothing persisted despite a 200). Never throws.
     */
    suspend fun postSamples(apiKey: String, tripId: String, samples: List<Sample>): Int = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext 0
        val arr = JSONArray()
        samples.forEach { s ->
            arr.put(
                JSONObject()
                    .put("lat", s.lat)
                    .put("lon", s.lon)
                    .put("speed_kmh", s.speedKmh)
                    // ISO-8601 string — NEVER an epoch Long.
                    .put("ts", Instant.ofEpochMilli(s.tsMillis).toString())
                    .apply {
                        s.etaMin?.let { put("eta_min", it) }
                        s.remainingKm?.let { put("remaining_km", it) }
                    }
            )
        }
        val payload = JSONObject().put("samples", arr).toString().toRequestBody(jsonMedia)
        try {
            client.newCall(keyed(apiKey, "/api/trips/$tripId/samples").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "postSamples HTTP ${resp.code}: $raw")
                    return@withContext 0
                }
                val stored = runCatching { JSONObject(raw).optInt("stored", -1) }.getOrDefault(-1)
                if (stored <= 0) {
                    Log.w(TAG, "postSamples 200 but stored=$stored (silent drop?): $raw")
                    return@withContext 0
                }
                stored
            }
        } catch (e: Exception) {
            Log.w(TAG, "postSamples failed: ${e.message}")
            0
        }
    }

    /**
     * End a trip -> `POST /api/trips/{id}/end`. Returns the summary (verified trip_id) or null.
     * Never throws.
     */
    suspend fun endTrip(apiKey: String, tripId: String, avgKmh: Double? = null): TripSummary? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .apply { if (avgKmh != null) put("avg_kmh", avgKmh) }
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(keyed(apiKey, "/api/trips/$tripId/end").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "endTrip HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val id = o.optString("trip_id").takeUnless { it.isNullOrBlank() }
                if (id == null) {
                    Log.w(TAG, "endTrip 200 but no trip_id: $raw")
                    return@withContext null
                }
                TripSummary(
                    tripId = id,
                    durationMin = o.optDouble("duration_min", 0.0),
                    sampleCount = o.optInt("sample_count", 0),
                    avgKmh = if (o.isNull("avg_kmh")) null else o.optDouble("avg_kmh").takeUnless { it.isNaN() },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "endTrip failed: ${e.message}")
            null
        }
    }

    /** Recent trips -> `GET /api/trips?limit=`. Empty on failure. Never throws. */
    suspend fun recentTrips(apiKey: String, limit: Int = 20): List<Trip> = withContext(Dispatchers.IO) {
        try {
            client.newCall(keyed(apiKey, "/api/trips?limit=$limit").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "recentTrips HTTP ${resp.code}: $raw")
                    return@withContext emptyList()
                }
                val arr = runCatching { JSONObject(raw).optJSONArray("trips") }.getOrNull()
                    ?: runCatching { JSONArray(raw) }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseTrip(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recentTrips failed: ${e.message}")
            emptyList()
        }
    }

    /** One trip by id -> `GET /api/trips/{id}`. Null if missing/failed. */
    suspend fun getTrip(apiKey: String, tripId: String): Trip? = withContext(Dispatchers.IO) {
        try {
            client.newCall(keyed(apiKey, "/api/trips/$tripId").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getTrip HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw).optJSONObject("trip") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withContext null
                parseTrip(o)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTrip failed: ${e.message}")
            null
        }
    }

    /** Congestion profile near a point -> `GET /api/congestion`. Null on failure. Never throws. */
    suspend fun congestion(
        apiKey: String,
        lat: Double, lon: Double,
        radiusM: Int = 1500,
        hour: Int? = null,
    ): Congestion? = withContext(Dispatchers.IO) {
        val path = buildString {
            append("/api/congestion?lat=$lat&lon=$lon&radius_m=$radiusM")
            if (hour != null) append("&hour=$hour")
        }
        try {
            client.newCall(keyed(apiKey, path).get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "congestion HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val byHour = mutableMapOf<Int, Double>()
                o.optJSONObject("by_hour")?.let { bh ->
                    bh.keys().forEach { k ->
                        runCatching { byHour[k.toInt()] = bh.optDouble(k) }
                    }
                }
                Congestion(
                    avgKmh = if (o.isNull("avg_kmh")) null else o.optDouble("avg_kmh").takeUnless { it.isNaN() },
                    sampleCount = o.optInt("sample_count", 0),
                    byHour = byHour,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "congestion failed: ${e.message}")
            null
        }
    }

    /** Log a destination search (query + result count + chosen place + where he was). Best-effort. */
    suspend fun logSearch(
        apiKey: String,
        query: String,
        results: Int?,
        chosenLabel: String?,
        chosenLat: Double?,
        chosenLon: Double?,
        nearLat: Double?,
        nearLon: Double?,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("query", query)
            .apply {
                results?.let { put("results", it) }
                chosenLabel?.let { put("chosen_label", it) }
                chosenLat?.let { put("chosen_lat", it) }
                chosenLon?.let { put("chosen_lon", it) }
                nearLat?.let { put("near_lat", it) }
                nearLon?.let { put("near_lon", it) }
            }
            .put("ts", Instant.now().toString())
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(keyed(apiKey, "/api/searches").post(payload).build()).execute().use { resp ->
                if (!resp.isSuccessful) Log.w(TAG, "logSearch HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logSearch failed: ${e.message}")
        }
        Unit
    }

    private fun parseTrip(o: JSONObject): Trip = Trip(
        tripId = o.optString("trip_id").ifBlank { o.optString("id") },
        destName = o.str("dest_name"),
        km = o.dbl("km"),
        etaMin = o.dbl("eta_min"),
        avgKmh = o.dbl("avg_kmh"),
        durationMin = o.dbl("duration_min"),
        sampleCount = if (o.isNull("sample_count")) null else o.optInt("sample_count"),
        mode = o.str("mode"),
        startedAt = o.str("started_at"),
        endedAt = o.str("ended_at"),
    )

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeUnless { it.isBlank() || it == "null" }

    private fun JSONObject.dbl(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    companion object {
        private const val TAG = "TripsCloudClient"
    }
}
