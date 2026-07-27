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

/**
 * "What's right here?" — a **category** POI search around a point (the destination, or the user).
 *
 * The killer case: you've routed to a venue ("Café de Paris, Monaco") but you actually need to
 * park. You don't know the car park's name — so this finds **parking first** within a short radius,
 * plus fuel + EV charging (the things you look for the moment you arrive). Nine times out of ten the
 * top hit is a car park.
 *
 * Unlike [PoiSearch] (a case-insensitive name-REGEX, inherently slow), this is a **tag** query
 * (`[amenity=parking]`) — indexed and fast (~0.3 s) on any Overpass. Same DoH client + Bearer token.
 * Best-effort: empty list on any failure.
 */
object NearbySearch {

    private const val TAG = "NearbySearch"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent; mikaelwestoo@gmail.com)"
    // Self-hosted Overpass (osm.osmike.com) — a TAG query is the fast indexed pattern our box serves
    // in ~0.3-0.75s (unlike the slow name-regex, which PoiSearch keeps on the public cluster).
    private val ENDPOINT = "${BuildConfig.OVERPASS_SELF_URL}/api/interpreter"

    /** Ranked so parking floats to the top of the list — the overwhelmingly common intent. */
    enum class Category(val rank: Int, val label: String) {
        PARKING(0, "Parking"),
        CHARGING(1, "EV charging"),
        FUEL(2, "Fuel"),
        OTHER(3, "Nearby"),
    }

    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val category: Category,
        val distanceM: Int,
        val detail: String?,   // "paid · covered · 120 spaces" etc.
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * Find parking (+ fuel + EV) within [radiusM] of ([lat],[lon]), nearest-first with parking
     * ranked above everything. Returns up to [limit] results.
     */
    suspend fun search(
        lat: Double,
        lon: Double,
        radiusM: Int = 400,
        limit: Int = 20,
    ): List<Place> = withContext(Dispatchers.IO) {
        val a = "(around:$radiusM,$lat,$lon)"
        // Tag filters only (indexed, fast). `nwr` = node/way/relation; `out center` gives ways a point.
        val ql = """
            [out:json][timeout:25];
            (
              nwr$a[amenity=parking];
              nwr$a[amenity=charging_station];
              nwr$a[amenity=fuel];
            );
            out center tags $limit;
        """.trimIndent()

        val req = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", UA)
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .post(FormBody.Builder().add("data", ql).build())
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "overpass HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val els = runCatching { JSONObject(raw).optJSONArray("elements") }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until els.length()).mapNotNull { i ->
                    val e = els.optJSONObject(i) ?: return@mapNotNull null
                    val tags = e.optJSONObject("tags") ?: JSONObject()
                    val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                    val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                    if (plat.isNaN() || plon.isNaN()) return@mapNotNull null
                    val cat = categoryOf(tags)
                    val distM = (NavGeo.haversineKm(lat, lon, plat, plon) * 1000.0).toInt()
                    Place(
                        name = nameOf(tags, cat),
                        lat = plat, lon = plon,
                        category = cat,
                        distanceM = distM,
                        detail = detailOf(tags, cat),
                    )
                }
                    // Nearest of each kind first, parking ranked above all. De-dupe repeats at ~same spot.
                    .distinctBy { "${it.name}|${it.lat.format()}|${it.lon.format()}" }
                    .sortedWith(compareBy({ it.category.rank }, { it.distanceM }))
                    .take(limit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "nearby failed: ${e.message}")
            emptyList()
        }
    }

    private fun categoryOf(tags: JSONObject): Category = when (tags.optString("amenity")) {
        "parking", "parking_entrance" -> Category.PARKING
        "charging_station" -> Category.CHARGING
        "fuel" -> Category.FUEL
        else -> Category.OTHER
    }

    private fun nameOf(tags: JSONObject, cat: Category): String {
        val n = tags.optString("name").takeUnless { it.isBlank() }
        if (n != null) return n
        val brand = tags.optString("brand").takeUnless { it.isBlank() }
        return brand ?: cat.label   // unnamed car park → just "Parking"
    }

    /** A one-line hint: for parking, whether it's paid / covered / how big. */
    private fun detailOf(tags: JSONObject, cat: Category): String? {
        val bits = mutableListOf<String>()
        if (cat == Category.PARKING) {
            when (tags.optString("fee")) { "yes" -> bits += "paid"; "no" -> bits += "free" }
            when (tags.optString("parking")) {
                "underground" -> bits += "underground"
                "multi-storey" -> bits += "multi-storey"
                "surface" -> {}
            }
            when (tags.optString("access")) { "private" -> bits += "private"; "customers" -> bits += "customers only" }
            tags.optString("capacity").takeUnless { it.isBlank() }?.let { bits += "$it spaces" }
        }
        return bits.joinToString(" · ").ifBlank { null }
    }

    private fun Double.format(): String = "%.5f".format(this)
}
