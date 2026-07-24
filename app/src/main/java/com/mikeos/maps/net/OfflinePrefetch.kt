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
 * "Google-Maps-style offline": preload the vector basemap for ~100 km around the user so the map is
 * instant and survives bad signal. Uses MapLibre's [OfflineManager] (tiles land in MapLibre's own
 * SQLite cache). We keep ONE rolling region centred on the user and re-download only when he moves
 * far ([REFRESH_KM]) — so the cache doesn't grow unbounded as he travels.
 *
 * Entirely best-effort: any failure is swallowed. Even with prefetch off, the map still works via
 * MapLibre's ambient cache + live tiles — this only makes it faster/offline-resilient.
 */
object OfflinePrefetch {

    private const val TAG = "OfflinePrefetch"
    private const val RADIUS_KM = 100.0     // preload this far around the user
    private const val REFRESH_KM = 40.0     // re-prefetch once he's moved this far from the last centre
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 13.0       // z13 ≈ street level; z0-13 over 200 km ≈ a few thousand tiles

    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var busy = false

    private val main = Handler(Looper.getMainLooper())

    /** Ensure ~100 km around (lat,lon) is cached. No-op if we already cover this area or are busy. */
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
                // Our ~100 km / z0-13 region is a few thousand tiles — under MapLibre's default
                // 6000-tile offline limit, so no need to raise it.
                val bounds = boundsAround(lat, lon, RADIUS_KM)
                val pixelRatio = app.resources.displayMetrics.density
                val def = OfflineTilePyramidRegionDefinition(
                    "${BuildConfig.BASEMAP_URL}/style.json", bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio,
                )
                val metadata = "mikemaps-around".toByteArray()
                // Drop old regions first (rolling window), then create + activate the new one.
                mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        regions?.forEach { r ->
                            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() {}
                                override fun onError(error: String) {}
                            })
                        }
                        create(mgr, def, metadata)
                    }

                    override fun onError(error: String) = create(mgr, def, metadata)
                })
            }.onFailure {
                busy = false
                Log.w(TAG, "prefetch failed: ${it.message}")
            }
        }
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
