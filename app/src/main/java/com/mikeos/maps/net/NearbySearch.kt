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
 * "What's here?" — a **category** POI search, two shapes:
 *  • [search] AROUND a point (the destination, or you) — parking-first. The killer case: you routed
 *    to a venue but need the car park whose name you don't know.
 *  • [searchAlongRoute] ALONG the road ahead — fuel/food/rest/charging strung along the whole route,
 *    ordered by when you'll pass them (drive-past order).
 *
 * Both are **tag** queries (`[amenity=…]`), the fast (~0.3-0.8s) indexed pattern our self-hosted
 * Overpass serves well (unlike [PoiSearch]'s slow name-regex). Best-effort: empty list on failure.
 */
object NearbySearch {

    private const val TAG = "NearbySearch"
    private const val UA = "MikeMaps/0.1 (MikeOS navigation agent; mikaelwestoo@gmail.com)"
    private val ENDPOINT = "${BuildConfig.OVERPASS_SELF_URL}/api/interpreter"

    /** Ranked so parking floats to the top when searching around a point (the common intent). */
    enum class Category(val rank: Int, val label: String) {
        PARKING(0, "Parking"),
        FUEL(1, "Fuel"),
        CHARGING(2, "EV charging"),
        FOOD(3, "Food & café"),
        SHOP(4, "Shop"),
        REST(5, "Rest & WC"),
        CASH(6, "ATM / bank"),
        OTHER(7, "Place"),
    }

    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val category: Category,
        val distanceM: Int,      // straight-line distance from the search anchor
        val alongM: Int?,        // distance ALONG the route (set for along-route results, else null)
        val detail: String?,     // "paid · covered · 120 spaces" etc.
    )

    // The union of everything we surface, as THREE value-regex clauses over the given area [a] (a point
    // or a whole polyline). Value-regex over a bounded spatial set is cheap (small candidate set) — and
    // 3 `around` clauses scale far better than 15 separate ones on a route corridor (~2.5s vs ~10s).
    private fun unionBody(a: String): String {
        val d = "$"   // literal $ (regex end-anchor) inside the Kotlin template
        return """
              nwr$a["amenity"~"^(parking|parking_entrance|fuel|charging_station|restaurant|cafe|fast_food|food_court|bank|atm|toilets)$d"];
              nwr$a["shop"~"^(supermarket|convenience)$d"];
              nwr$a["highway"~"^(rest_area|services)$d"];
        """.trimIndent()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Find POIs within [radiusM] of ([lat],[lon]) — nearest-first, parking ranked above all. */
    suspend fun search(
        lat: Double,
        lon: Double,
        radiusM: Int = 400,
        limit: Int = 24,
    ): List<Place> = withContext(Dispatchers.IO) {
        val a = "(around:$radiusM,$lat,$lon)"
        val ql = "[out:json][timeout:25];\n(\n${unionBody(a)}\n);\nout center tags $limit;"
        val places = run(ql) { plat, plon, cat, tags ->
            Place(
                name = nameOf(tags, cat), lat = plat, lon = plon, category = cat,
                distanceM = (NavGeo.haversineKm(lat, lon, plat, plon) * 1000.0).toInt(),
                alongM = null, detail = detailOf(tags, cat),
            )
        }
        places
            .distinctBy { key(it) }
            .sortedWith(compareBy({ it.category.rank }, { it.distanceM }))
            .take(limit)
    }

    /**
     * Find POIs within [corridorM] of the road AHEAD (the route from the current position to the
     * destination), ordered by drive-past order (distance ALONG the route). For road-trip stops:
     * fuel, food, rest, charging, parking.
     */
    suspend fun searchAlongRoute(
        route: List<PolylineCodec.LatLon>,
        curLat: Double,
        curLon: Double,
        corridorM: Int = 200,
        limit: Int = 30,
    ): List<Place> = withContext(Dispatchers.IO) {
        val ahead = NavGeo.routeAhead(route, curLat, curLon)
        if (ahead.size < 2) return@withContext emptyList()
        // Subsample the road ahead so the around() coord-list stays small (~25 pts → fast, ~2-5s).
        val step = maxOf(1, ahead.size / 25)
        val pts = ahead.filterIndexed { i, _ -> i % step == 0 }.ifEmpty { ahead }
        val coords = pts.joinToString(",") { "${it.lat},${it.lon}" }
        val a = "(around:$corridorM,$coords)"
        val ql = "[out:json][timeout:25];\n(\n${unionBody(a)}\n);\nout center tags $limit;"
        val places = run(ql) { plat, plon, cat, tags ->
            val along = NavGeo.alongRouteM(ahead, plat, plon)
            Place(
                name = nameOf(tags, cat), lat = plat, lon = plon, category = cat,
                distanceM = (NavGeo.haversineKm(curLat, curLon, plat, plon) * 1000.0).toInt(),
                alongM = along, detail = detailOf(tags, cat),
            )
        }
        places
            .distinctBy { key(it) }
            .sortedBy { it.alongM ?: Int.MAX_VALUE }   // drive-past order
            .take(limit)
    }

    // ---- shared plumbing ----------------------------------------------------------------------

    private inline fun run(ql: String, build: (Double, Double, Category, JSONObject) -> Place): List<Place> {
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", UA)
            .apply { if (BuildConfig.OSM_TOKEN.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.OSM_TOKEN}") }
            .post(FormBody.Builder().add("data", ql).build())
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "overpass HTTP ${resp.code}"); return emptyList() }
                val els = runCatching { JSONObject(raw).optJSONArray("elements") }.getOrNull() ?: return emptyList()
                (0 until els.length()).mapNotNull { i ->
                    val e = els.optJSONObject(i) ?: return@mapNotNull null
                    val tags = e.optJSONObject("tags") ?: JSONObject()
                    val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                    val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                    if (plat.isNaN() || plon.isNaN()) return@mapNotNull null
                    build(plat, plon, categoryOf(tags), tags)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "nearby failed: ${e.message}"); emptyList()
        }
    }

    private fun key(p: Place) = "${p.name}|${"%.5f".format(p.lat)}|${"%.5f".format(p.lon)}"

    private fun categoryOf(tags: JSONObject): Category {
        when (tags.optString("amenity")) {
            "parking", "parking_entrance" -> return Category.PARKING
            "fuel" -> return Category.FUEL
            "charging_station" -> return Category.CHARGING
            "restaurant", "fast_food", "cafe", "food_court" -> return Category.FOOD
            "toilets" -> return Category.REST
            "atm", "bank" -> return Category.CASH
        }
        when (tags.optString("shop")) { "supermarket", "convenience" -> return Category.SHOP }
        when (tags.optString("highway")) { "rest_area", "services" -> return Category.REST }
        return Category.OTHER
    }

    private fun nameOf(tags: JSONObject, cat: Category): String {
        tags.optString("name").takeUnless { it.isBlank() }?.let { return it }
        tags.optString("brand").takeUnless { it.isBlank() }?.let { return it }
        return cat.label
    }

    /** A one-line hint: parking → paid/covered/size; food → cuisine; fuel → brand. */
    private fun detailOf(tags: JSONObject, cat: Category): String? {
        val bits = mutableListOf<String>()
        when (cat) {
            Category.PARKING -> {
                when (tags.optString("fee")) { "yes" -> bits += "paid"; "no" -> bits += "free" }
                when (tags.optString("parking")) { "underground" -> bits += "underground"; "multi-storey" -> bits += "multi-storey" }
                when (tags.optString("access")) { "private" -> bits += "private"; "customers" -> bits += "customers only" }
                tags.optString("capacity").takeUnless { it.isBlank() }?.let { bits += "$it spaces" }
            }
            Category.FOOD -> {
                tags.optString("cuisine").takeUnless { it.isBlank() }?.let { bits += it.replace("_", " ").replace(";", ", ") }
            }
            Category.CHARGING, Category.FUEL, Category.SHOP -> {
                tags.optString("brand").takeUnless { it.isBlank() }?.let { bits += it }
            }
            else -> {}
        }
        return bits.joinToString(" · ").ifBlank { null }
    }
}
