package com.mikeos.maps.net

import android.util.Log
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Named-POI search via **OSM Overpass** — finds places Nominatim's address-first `/search` misses
 * (a chapel, restaurant, beach, viewpoint called by name). Nominatim returned three copies of
 * *Avenue de l'Ange Gardien* but never the actual "L'Ange Gardien" POI a few metres away; Overpass
 * does. Biased to a box around the user, name-substring (case-insensitive), keyless + free.
 *
 * Only used on the EXPLICIT search (not type-ahead) to keep Overpass load light. DoH client for
 * this ROM's flaky DNS. Best-effort: empty on any failure.
 */
object PoiSearch {

    private const val TAG = "PoiSearch"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent; mikaelwestoo@gmail.com)"
    private val ENDPOINT = "${BuildConfig.OVERPASS_URL}/api/interpreter"
    private const val RADIUS_M = 60_000   // 60 km proximity radius for named POIs

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun search(
        rawQuery: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 6,
    ): List<Geocoder.Place> = withContext(Dispatchers.IO) {
        // POI name = the part before any comma, minus French articles + punctuation. Keep letters/
        // digits/spaces only, so what's interpolated into the Overpass regex can't inject anything.
        val cleaned = rawQuery.substringBefore(",")
            .lowercase()
            .replace(Regex("\\b(l'|d'|le|la|les|du|de|des|et|the)\\b|'"), " ")
            .replace(Regex("[^\\p{L}0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        // Match names that contain ALL the words (any order/position) — so "pierre vacances" finds
        // "Pierre et Vacances", not just an exact contiguous phrase. Each word is a separate AND
        // filter on the element's name.
        val words = cleaned.split(" ").filter { it.length >= 3 }.take(4)
        if (words.isEmpty()) return@withContext emptyList()
        val filter = words.joinToString("") { "[\"name\"~\"$it\",i]" }

        // Proximity query: fetch ALL name matches within a radius (a real circle, `around:`), so the
        // NEAREST is guaranteed in the set — then the caller ranks closest-first. Nominatim's
        // importance-limited top-N would otherwise miss the nearest in a big city. Farther unique
        // names fall through to the worldwide geocoder fallback elsewhere.
        val around = "(around:$RADIUS_M,$nearLat,$nearLon)"
        val fetch = maxOf(limit, 60)
        val ql = """
            [out:json][timeout:25];
            (node$around$filter;
             way$around$filter;);
            out center $fetch;
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
                    val tags = e.optJSONObject("tags") ?: return@mapNotNull null
                    val nm = tags.optString("name").takeUnless { it.isBlank() } ?: return@mapNotNull null
                    val lat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                    val lon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                    if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                    val city = tags.optString("addr:city").takeUnless { it.isBlank() }
                    val label = if (city != null) "$nm, $city" else nm
                    Geocoder.Place(name = label, lat = lat, lon = lon)
                }.distinctBy { it.name.lowercase() }.take(60)
            }
        } catch (e: Exception) {
            Log.w(TAG, "overpass failed: ${e.message}")
            emptyList()
        }
    }
}
