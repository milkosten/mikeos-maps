package com.mikeos.maps.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.nav.NavGeo
import com.mikeos.maps.ui.MapLibreInit
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos
import kotlin.math.max

/**
 * "Google-Maps-style offline": preload the vector basemap around the user so the map is instant and
 * survives bad signal. Uses MapLibre's [OfflineManager] (tiles land in MapLibre's own SQLite cache).
 * We keep ONE rolling region centred on the user and refresh it as he moves ([REFRESH_KM]).
 *
 * STYLE FRESHNESS (important — was a real bug): an offline region PINS the style + sprites + glyphs it
 * was created with and MapLibre serves those pinned copies WITHOUT revalidation — so a server-side
 * change to `$BASEMAP_URL/style.json` would never reach a phone that already has a region (it renders
 * the old style forever). To fix that we [OfflineRegion.invalidate] the covering region every time we
 * run: invalidate re-checks every resource (style included) against the server via ETag and only
 * re-downloads what changed — cheap when nothing changed, and it lets style updates flow through.
 *
 * ZOOM: we prefetch up to z[MAX_ZOOM] so street-level tiles (where POIs live) are cached offline, over
 * a [RADIUS_KM] bubble around the user (his daily-drive region). Anything beyond still loads live when
 * online. The pyramid is bounded by [TILE_LIMIT] so storage stays sane.
 *
 * Entirely best-effort: any failure is swallowed. Even with prefetch off, the map still works via
 * MapLibre's ambient cache + live tiles — this only makes it faster/offline-resilient.
 */
object OfflinePrefetch {

    private const val TAG = "OfflinePrefetch"
    private const val RADIUS_KM = 30.0      // preload this far around the user (daily-drive bubble)
    private const val REFRESH_KM = 12.0     // re-centre once he's moved this far from the last centre
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 15.0       // z15 = the basemap's max source zoom → street-level POIs cached
    private const val TILE_LIMIT = 20_000L  // z0-15 over ~30 km ≈ a few thousand tiles; keep headroom

    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var busy = false

    private val main = Handler(Looper.getMainLooper())

    /** Ensure the area around (lat,lon) is cached AND its style is revalidated. No-op if busy or if
     *  we already re-centred within [REFRESH_KM] this session. */
    fun ensureAround(context: Context, lat: Double, lon: Double) {
        val pLat = lastLat
        val pLon = lastLon
        if (pLat != null && pLon != null && NavGeo.haversineKm(pLat, pLon, lat, lon) < REFRESH_KM) return
        if (busy) return
        busy = true
        lastLat = lat
        lastLon = lon
        val app = context.applicationContext
        // OfflineManager must be driven on the main thread (its FileSource requires a Looper).
        main.post {
            runCatching {
                MapLibreInit.ensure(app)
                val mgr = OfflineManager.getInstance(app)
                mgr.setOfflineMapboxTileCountLimit(TILE_LIMIT)
                val here = LatLng(lat, lon)
                val bounds = boundsAround(lat, lon, RADIUS_KM)
                val pixelRatio = app.resources.displayMetrics.density
                val def = OfflineTilePyramidRegionDefinition(
                    "${BuildConfig.BASEMAP_URL}/style.json", bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio,
                )
                val metadata = "mikemaps-around".toByteArray()
                mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        // Keep the one region that already covers where we are (revalidate it so a
                        // new server style flows in); delete stale far-away regions; create one if none.
                        var covering: OfflineRegion? = null
                        regions?.forEach { r ->
                            val defn = runCatching { r.definition }.getOrNull()
                            val inside = defn?.bounds?.contains(here) == true
                            if (inside && covering == null) {
                                covering = r
                            } else {
                                r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() {}
                                    override fun onError(error: String) {}
                                })
                            }
                        }
                        val cov = covering
                        if (cov != null) {
                            refresh(cov)
                        } else {
                            create(mgr, def, metadata)
                        }
                    }

                    override fun onError(error: String) = create(mgr, def, metadata)
                })
            }.onFailure {
                busy = false
                Log.w(TAG, "prefetch failed: ${it.message}")
            }
        }
    }

    /** Revalidate an existing region's resources (style + tiles) against the server via ETag, then
     *  make sure it keeps downloading any missing tiles. Cheap when nothing changed. */
    private fun refresh(region: OfflineRegion) {
        region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
            override fun onInvalidate() {
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                busy = false
                Log.i(TAG, "revalidated offline region (style + tiles refreshed)")
            }

            override fun onError(error: String) {
                // Even if invalidate fails, resume downloading so we don't get stuck.
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                busy = false
                Log.w(TAG, "invalidate failed: $error")
            }
        })
    }

    private fun create(mgr: OfflineManager, def: OfflineTilePyramidRegionDefinition, metadata: ByteArray) {
        mgr.createOfflineRegion(def, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                busy = false
                Log.i(TAG, "prefetching ~${RADIUS_KM.toInt()} km around the user (z$MIN_ZOOM-$MAX_ZOOM)")
            }

            override fun onError(error: String) {
                busy = false
                Log.w(TAG, "createOfflineRegion: $error")
            }
        })
    }

    private fun boundsAround(lat: Double, lon: Double, radiusKm: Double): LatLngBounds {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * max(0.2, cos(Math.toRadians(lat))))
        return LatLngBounds.Builder()
            .include(LatLng(lat + dLat, lon + dLon))
            .include(LatLng(lat - dLat, lon - dLon))
            .build()
    }
}
