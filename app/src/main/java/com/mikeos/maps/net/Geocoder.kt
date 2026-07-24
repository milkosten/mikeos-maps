package com.mikeos.maps.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Turns a destination NAME ("Nice city center") into coordinates via **OSM Nominatim**
 * (`GET https://nominatim.openstreetmap.org/search?q=&format=json&limit=1`). Keyless,
 * zero-cost. A descriptive User-Agent is required by the Nominatim usage policy.
 *
 * Uses the **DoH** OkHttp client because this ROM's system DNS intermittently fails.
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent; mikaelwestoo@gmail.com)"

    data class Place(val name: String, val lat: Double, val lon: Double)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Type-ahead: up to [limit] candidate places. When [nearLat]/[nearLon] are given, results are
     * first BIASED to a ~330 km box around the user (so "Villefranche" near Nice wins over the one
     * in Canada); only if that finds nothing do we fall back to a worldwide search. Empty on failure.
     */
    suspend fun search(
        query: String,
        limit: Int = 6,
        nearLat: Double? = null,
        nearLon: Double? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        if (nearLat != null && nearLon != null) {
            val d = 1.5   // ~165 km box — local trips; farther places fall through to worldwide below
            val viewbox = "${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}"
            val local = run(
                "https://nominatim.openstreetmap.org/search?q=$enc&format=json&limit=$limit" +
                    "&viewbox=$viewbox&bounded=1"
            )
            if (local.isNotEmpty()) return@withContext local
        }
        run("https://nominatim.openstreetmap.org/search?q=$enc&format=json&limit=$limit")
    }

    private suspend fun run(url: String): List<Place> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
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

    /** Geocode a free-text destination to a single best hit. Null if nothing found / failed. */
    suspend fun geocode(query: String): Place? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext null
        val url = "https://nominatim.openstreetmap.org/search?q=" +
            URLEncoder.encode(q, "UTF-8") + "&format=json&limit=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "geocode HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return@withContext null
                if (arr.length() == 0) return@withContext null
                val o = arr.getJSONObject(0)
                val lat = o.optString("lat").toDoubleOrNull() ?: return@withContext null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@withContext null
                val name = o.optString("display_name").takeUnless { it.isBlank() } ?: q
                Place(name = name, lat = lat, lon = lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed: ${e.message}")
            null
        }
    }
}
