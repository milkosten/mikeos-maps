package com.mikeos.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.nav.NavCamera
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.PolylineCodec
import com.mikeos.maps.ui.theme.MikeAccent
import com.mikeos.maps.ui.theme.MikeGreen
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The full-screen, map-first surface — MapLibre GL Native rendering the self-hosted OSM basemap
 * (mikeos-basemap) with:
 *  • a live "you are here" puck ([location], the ONE daemon fix) that MOVES as Mike moves,
 *  • camera that FOLLOWS him while [follow] is on (driver's view), and
 *  • the active route traced ahead.
 *
 * Panning the map turns [follow] off (via [onUserPan]) so Mike can look around; the recenter
 * button flips it back on. On the first fix the camera jumps to ~5 km ([INITIAL_ZOOM]); after that
 * it keeps whatever zoom Mike set. Basemap tiles resolve via our DoH client (see [MapLibreInit]);
 * © OpenStreetMap attribution rides in the style.
 */
@Composable
fun NavigationMap(
    location: DaemonLocation.Fix?,
    routePoints: List<PolylineCodec.LatLon>,
    follow: Boolean,
    navigating: Boolean,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val accent = MikeAccent.toArgb()
    val green = MikeGreen.toArgb()
    val holder = remember { NavMapHolder() }
    val currentOnUserPan by rememberUpdatedState(onUserPan)

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.also { mv ->
                mv.getMapAsync { map ->
                    holder.map = map
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.isRotateGesturesEnabled = true
                    // User drag → stop following so he can look around.
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) { currentOnUserPan() }
                        override fun onMove(detector: MoveGestureDetector) {}
                        override fun onMoveEnd(detector: MoveGestureDetector) {}
                    })
                    map.setStyle(Style.Builder().fromUri("${BuildConfig.BASEMAP_URL}/style.json")) { style ->
                        holder.style = style
                        // Route line.
                        style.addSource(GeoJsonSource(SRC_ROUTE))
                        style.addLayer(
                            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                                PropertyFactory.lineColor(accent),
                                PropertyFactory.lineWidth(6f),
                                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            ),
                        )
                        // Location puck: soft accuracy ring + solid dot.
                        style.addSource(GeoJsonSource(SRC_ME))
                        style.addLayer(
                            CircleLayer(LYR_ME_RING, SRC_ME).withProperties(
                                PropertyFactory.circleColor(accent),
                                PropertyFactory.circleOpacity(0.18f),
                                PropertyFactory.circleRadius(22f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(LYR_ME_DOT, SRC_ME).withProperties(
                                PropertyFactory.circleColor(accent),
                                PropertyFactory.circleRadius(7f),
                                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                                PropertyFactory.circleStrokeWidth(3f),
                            ),
                        )
                        holder.render(location, routePoints, follow, navigating)
                    }
                }
            }
        },
        update = { holder.render(location, routePoints, follow, navigating) },
    )
}

/** Pushes location/route into the style's sources and drives the follow camera. */
private class NavMapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    private var centeredOnce = false
    private var lastFittedRoute: List<PolylineCodec.LatLon>? = null
    // Smoothed speed for the adaptive nav zoom (raw GPS speed is noisy → EMA to avoid zoom jitter).
    private var smoothedKmh = 0.0

    fun render(loc: DaemonLocation.Fix?, points: List<PolylineCodec.LatLon>, follow: Boolean, navigating: Boolean) {
        val s = style ?: return
        val routeGeom: Geometry =
            if (points.size >= 2) LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
            else EMPTY
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeGeom)
        val meGeom: Geometry = if (loc != null) Point.fromLngLat(loc.lon, loc.lat) else EMPTY
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meGeom)

        val m = map ?: return
        if (loc != null && follow) {
            // While NAVIGATING, zoom adapts to speed (close when slow, wide when fast); otherwise
            // keep the initial ~5 km on first center and whatever zoom Mike set afterwards.
            val zoom = if (navigating) {
                val raw = loc.speedKmh ?: 0.0
                smoothedKmh = if (centeredOnce) smoothedKmh * 0.7 + raw * 0.3 else raw
                NavCamera.zoomForSpeed(smoothedKmh, loc.lat, m.height.toDouble())
            } else {
                if (!centeredOnce) INITIAL_ZOOM else m.cameraPosition.zoom
            }
            centeredOnce = true
            val pos = CameraPosition.Builder().target(LatLng(loc.lat, loc.lon)).zoom(zoom).build()
            runCatching { m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), CAMERA_MS) }
        } else if (!follow && points.size >= 2 && lastFittedRoute !== points) {
            // Not following (route preview, or no fix yet) — frame the whole route once.
            val b = LatLngBounds.Builder()
            points.forEach { b.include(LatLng(it.lat, it.lon)) }
            runCatching { m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), FIT_PADDING_PX)) }
            lastFittedRoute = points
        }
    }

    companion object {
        private val EMPTY: Geometry = LineString.fromLngLats(emptyList())
    }
}

private const val SRC_ROUTE = "route-src"
private const val LYR_ROUTE = "route-line"
private const val SRC_ME = "me-src"
private const val LYR_ME_RING = "me-ring"
private const val LYR_ME_DOT = "me-dot"
private const val INITIAL_ZOOM = 13.0    // ~5 km across on open
private const val FIT_PADDING_PX = 110
private const val CAMERA_MS = 700

/**
 * A [MapView] whose Android lifecycle is forwarded from the current Compose lifecycle owner, with
 * late-attach catch-up and teardown on dispose.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibreInit.ensure(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
