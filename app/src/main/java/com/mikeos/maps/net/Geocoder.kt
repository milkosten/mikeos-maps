package com.mikeos.maps.net

import android.util.Log
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Turns a destination NAME ("Nice city center") or a partial address ("15 boulevard general") into
 * coordinates.
 *
 * Two engines, in order:
 *  1. **Photon** (self-hosted, [BuildConfig.PHOTON_URL]) — prefix/fuzzy, LOCATION-BIASED search: it
 *     handles as-you-type partials and ranks by nearness, so a user in Villefranche typing "15
 *     boulevard general" gets the local Boulevard Général — not Virginia Beach. This is the primary.
 *  2. **Nominatim** ([BuildConfig.NOMINATIM_URL]) — whole-address/place geocoder, used to fill/fallback
 *     (and when Photon is unreachable, so an OTA that ships before Photon is live still works).
 *
 * Uses the **DoH** OkHttp client because this ROM's system DNS intermittently fails.
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent; mikaelwestoo@gmail.com)"
    // If Photon returns at least this many hits, trust it and DON'T pollute with Nominatim's fuzzy
    // address guesses (they crowd out real POIs like "Super U"). Below it, Nominatim fills the gap.
    private const val PHOTON_MIN = 4

    data class Place(val name: String, val lat: Double, val lon: Double, val category: String? = null)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // Common French street-type abbreviations Nominatim doesn't always expand. Applied per-word so
    // "111 bd carnot" also matches "111 boulevard carnot". (Typo tolerance — "carrot"→"carnot" — is
    // NOT solved here; that needs the self-hosted semantic search, G2.)
    private val ABBREV = mapOf(
        "bd" to "boulevard", "bld" to "boulevard", "blvd" to "boulevard", "boul" to "boulevard",
        "av" to "avenue", "ave" to "avenue", "pl" to "place", "rte" to "route",
        "che" to "chemin", "imp" to "impasse", "sq" to "square", "all" to "allée",
    )

    private fun expandQuery(q: String): String =
        q.split(Regex("\\s+")).joinToString(" ") { w -> ABBREV[w.lowercase().trimEnd('.')] ?: w }

    private fun key(p: Place) = "${(p.lat * 1e4).toLong()},${(p.lon * 1e4).toLong()}"

    /**
     * Type-ahead: up to [limit] candidate places. When [nearLat]/[nearLon] are given we MERGE a
     * location-biased search (a ~165 km box around the user) with a worldwide one and DEDUPE — so we
     * return many candidates (≥5 for real queries) and the caller ranks by distance, meaning a
     * namesake far away can never outrank the local one. Empty on failure.
     */
    suspend fun search(
        query: String,
        limit: Int = 8,
        nearLat: Double? = null,
        nearLon: Double? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val q = expandQuery(query.trim())
        if (q.isBlank()) return@withContext emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val out = LinkedHashMap<String, Place>()   // insertion-ordered, deduped by rounded coords

        // 1) Photon FIRST — partial-tolerant, TWO passes. Photon's soft lat/lon bias is too weak: a
        //    "39 avenue de republic" typed in Nice returned only streets in DR Congo / Sudan (a far
        //    namesake outranked the local street, which never even entered the result set). So we do a
        //    HARD local-bbox pass around the user first (a namesake 3000 km away is excluded outright),
        //    THEN a worldwide pass for genuinely far destinations. The caller ranks by distance, so the
        //    local hit wins when it exists; far ones still appear (ranked last) when nothing local does.
        if (BuildConfig.PHOTON_URL.isNotBlank()) {
            if (nearLat != null && nearLon != null) {
                val d = 2.5   // ~275 km box; Photon bbox = minLon,minLat,maxLon,maxLat
                val bbox = "${nearLon - d},${nearLat - d},${nearLon + d},${nearLat + d}"
                for (p in runPhoton("${BuildConfig.PHOTON_URL}/api?q=$enc&limit=$limit&bbox=$bbox&lat=$nearLat&lon=$nearLon"))
                    out.putIfAbsent(key(p), p)
            }
            val bias = if (nearLat != null && nearLon != null) "&lat=$nearLat&lon=$nearLon" else ""
            for (p in runPhoton("${BuildConfig.PHOTON_URL}/api?q=$enc&limit=$limit$bias"))
                out.putIfAbsent(key(p), p)
        }

        // 2) Nominatim — ONLY as a fallback when Photon came up short. Photon (planet, location-biased)
        //    is the good answer; Nominatim, asked for the SAME query, returns fuzzy address guesses that
        //    used to get merged in and — being physically nearby — crowd out Photon's real hits
        //    (e.g. "SuperU" → random Beaulieu/Nice street addresses instead of the Super U store). So we
        //    only reach for it when Photon gave us little to work with.
        if (out.size < PHOTON_MIN && nearLat != null && nearLon != null) {
            val d = 1.5   // ~165 km box around the user
            val viewbox = "${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}"
            for (p in run("${BuildConfig.NOMINATIM_URL}/search?q=$enc&format=json&limit=$limit&viewbox=$viewbox&bounded=1"))
                out.putIfAbsent(key(p), p)
        }
        if (out.size < PHOTON_MIN) {
            for (p in run("${BuildConfig.NOMINATIM_URL}/search?q=$enc&format=json&limit=$limit"))
                out.putIfAbsent(key(p), p)
        }
        out.values.toList()
    }

    /** Query Photon (GeoJSON FeatureCollection: geometry.coordinates=[lon,lat], properties=address). */
    private suspend fun runPhoton(url: String): List<Place> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()   // Photon down → caller falls back to Nominatim
                val body = resp.body?.string().orEmpty()
                val feats = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until feats.length()).mapNotNull { i ->
                    val f = feats.optJSONObject(i) ?: return@mapNotNull null
                    val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                    val lon = coords.optDouble(0).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    val lat = coords.optDouble(1).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    val props = f.optJSONObject("properties")
                    val cat = props?.optString("osm_value")?.takeUnless { it.isBlank() }
                    Place(name = photonLabel(props), lat = lat, lon = lon, category = cat)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "photon failed: ${e.message}")
            emptyList()
        }
    }

    /** Build a readable label from Photon's structured address properties. */
    private fun photonLabel(p: JSONObject?): String {
        if (p == null) return "?"
        val name = p.optString("name").takeUnless { it.isBlank() }
        val house = p.optString("housenumber").takeUnless { it.isBlank() }
        val street = p.optString("street").takeUnless { it.isBlank() }
        val city = p.optString("city").takeUnless { it.isBlank() }
            ?: p.optString("district").takeUnless { it.isBlank() }
            ?: p.optString("country").takeUnless { it.isBlank() }   // fallback locator (e.g. Monaco)
        val head = when {
            // A named POI (Super U, a café, a station) → show its NAME. This was the bug behind
            // "SuperU → zero results": a Super U store also has a street ("Rue du 8 Mai"), and labelling
            // it by the street made the result read as a plain address, so it looked like the store
            // wasn't found. Name wins; the street is only the label for an address that HAS no name.
            name != null -> name
            street != null -> listOfNotNull(house, street).joinToString(" ")
            else -> city ?: "?"
        }
        // "Name, City" — drop region + country: when you're finding a place nearby, "Provence-Alpes-
        // Côte d'Azur, France" is noise. The city/neighbourhood is the only locating word that helps.
        return listOfNotNull(head, city.takeIf { head != city }).distinct().joinToString(", ")
    }

    private suspend fun run(url: String): List<Place> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                    val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                    val name = o.optString("display_name").takeUnless { it.isBlank() } ?: return@mapNotNull null
                    Place(name = name, lat = lat, lon = lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Geocode a free-text destination to a single best hit. When [nearLat]/[nearLon] are given, a
     * local match wins over a far-away namesake (biased box first, then worldwide). Null if nothing
     * found / failed. Applies the same abbreviation expansion as [search].
     */
    suspend fun geocode(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
    ): Place? = search(query, limit = 1, nearLat = nearLat, nearLon = nearLon).firstOrNull()
}
