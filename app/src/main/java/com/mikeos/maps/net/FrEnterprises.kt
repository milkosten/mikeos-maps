package com.mikeos.maps.net

import android.util.Log
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Open French business POIs (SIRENE) from our self-hosted **france-enterprises-api** (242 box) — the
 * shops/cafés/companies that aren't in OpenStreetMap. Returns [NearbySearch.Place] so the map overlay
 * renders them exactly like OSM POIs. The app overlay MERGES these with Overpass results (OSM first,
 * these fill the gaps). Bearer-gated with OSM_TOKEN. Open data only — no corporate/Google API.
 */
object FrEnterprises {

    private const val TAG = "FrEnterprises"
    private val ENDPOINT = BuildConfig.FR_ENTERPRISES_URL

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Businesses inside the viewport [south,west,north,east]. Best-effort: empty list on any failure. */
    /** Fire-and-forget: ask the backend to crawl + cache a few businesses' websites in this viewport
     *  (chrome-pool), building the enrichment DB where the user actually browses. Best-effort; the
     *  response is ignored (the backend rate-limits: 5/call + 24 h freshness). */
    suspend fun triggerEnrich(south: Double, west: Double, north: Double, east: Double) = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT/enrich?bbox=$west,$south,$east,$north&limit=1"
        val req = Request.Builder()
            .url(url)
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .build()
        runCatching { client.newCall(req).execute().use { } }
        Unit
    }

    suspend fun searchInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int = 300,
    ): List<NearbySearch.Place> = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT/near?bbox=$west,$south,$east,$north&limit=$limit"
        val req = Request.Builder()
            .url(url)
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "http ${resp.code}"); return@withContext emptyList() }
                val arr = runCatching { JSONObject(resp.body?.string().orEmpty()).optJSONArray("pois") }
                    .getOrNull() ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").takeUnless { it.isBlank() } ?: return@mapNotNull null
                    val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                    if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                    val cat = categoryOf(o.optString("category"))
                    NearbySearch.Place(
                        name = name, lat = lat, lon = lon,
                        category = cat,
                        distanceM = 0, alongM = null, detail = null,
                        icon = NearbySearch.iconForKind(o.optString("kind").takeUnless { it.isBlank() }, cat),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fr-enterprises failed: ${e.message}"); emptyList()
        }
    }

    private fun categoryOf(s: String?): NearbySearch.Category = when (s) {
        "FOOD" -> NearbySearch.Category.FOOD
        "SHOP" -> NearbySearch.Category.SHOP
        "CASH" -> NearbySearch.Category.CASH
        "FUEL" -> NearbySearch.Category.FUEL
        "CHARGING" -> NearbySearch.Category.CHARGING
        "REST" -> NearbySearch.Category.REST
        "PARKING" -> NearbySearch.Category.PARKING
        else -> NearbySearch.Category.OTHER
    }
}
