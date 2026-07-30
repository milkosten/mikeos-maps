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
 * What OSM knows about a tapped place — category, hours, phone, website. Any field may be null.
 *
 * [at] fetches the full OSM tags from our self-hosted Overpass (fast, ~0.3 s) and distils the
 * human-useful bits, matching the OSM object by name near the tapped point.
 */
data class PoiDetails(
    val category: String? = null,      // human "Supermarket", "Restaurant · Italian"
    val openingHours: String? = null,  // raw OSM opening_hours string
    val phone: String? = null,
    val website: String? = null,
    val hoursFromWeb: Boolean = false, // hours came from the crawled website (not OSM) → show a note
) {
    companion object {
        private const val TAG = "PoiDetails"
        private const val UA = "MikeMaps/0.1 (MikeOS navigation agent)"
        private val ENDPOINT = "${BuildConfig.OVERPASS_SELF_URL}/api/interpreter"

        private val client: OkHttpClient = OkHttpClient.Builder()
            .dns(Doh.dns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        suspend fun at(name: String, lat: Double, lon: Double): PoiDetails? = withContext(Dispatchers.IO) {
            val ql = "[out:json][timeout:15];nwr(around:45,$lat,$lon)[name];out center tags 40;"
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
                    // Pick the element that matches the tapped name (exact ⟶ contains ⟶ nearest).
                    var best: JSONObject? = null
                    var bestScore = Double.MAX_VALUE
                    val wanted = name.trim().lowercase()
                    for (i in 0 until els.length()) {
                        val e = els.optJSONObject(i) ?: continue
                        val t = e.optJSONObject("tags") ?: continue
                        val nm = t.optString("name").lowercase()
                        val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                        val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                        val dist = NavGeo.haversineKm(lat, lon, plat, plon) * 1000
                        val nameScore = when {
                            nm == wanted -> 0.0
                            nm.isNotEmpty() && (nm.contains(wanted) || wanted.contains(nm)) -> 500.0
                            else -> 5000.0
                        }
                        val score = nameScore + dist
                        if (score < bestScore) { bestScore = score; best = t }
                    }
                    val t = best ?: return@withContext null
                    fun tag(vararg keys: String): String? =
                        keys.firstNotNullOfOrNull { k -> t.optString(k).takeUnless { it.isBlank() } }
                    PoiDetails(
                        category = humanCategory(t),
                        openingHours = tag("opening_hours"),
                        phone = tag("phone", "contact:phone", "contact:mobile"),
                        website = tag("website", "contact:website", "url"),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "poi details failed: ${e.message}"); null
            }
        }

        /** A readable category from OSM tags — "Restaurant · Italian", "Supermarket", "Bank"… */
        private fun humanCategory(t: JSONObject): String? {
            val kind = listOf("shop", "amenity", "tourism", "leisure", "office", "healthcare", "craft")
                .firstNotNullOfOrNull { t.optString(it).takeUnless { v -> v.isBlank() } } ?: return null
            val label = kind.split(";").first().replace('_', ' ').replaceFirstChar { it.uppercase() }
            val cuisine = t.optString("cuisine").takeUnless { it.isBlank() }
                ?.split(";")?.firstOrNull()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
            return if (cuisine != null) "$label · $cuisine" else label
        }
    }
}
