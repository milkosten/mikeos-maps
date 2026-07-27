package com.mikeos.maps.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import com.mikeos.maps.nav.NavGeo
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.MapAnalytics
import com.mikeos.maps.net.PolylineCodec
import com.mikeos.maps.ui.theme.MikeAccent
import com.mikeos.maps.ui.theme.MikeGreen
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
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
    headingUp: Boolean,
    bearingDeg: Double?,
    onUserPan: () -> Unit,
    onPoiTap: (name: String, lat: Double, lon: Double) -> Unit,
    onMapTapEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val accent = MikeAccent.toArgb()
    val green = MikeGreen.toArgb()
    val holder = remember { NavMapHolder() }
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    val currentOnPoiTap by rememberUpdatedState(onPoiTap)
    val currentOnMapTapEmpty by rememberUpdatedState(onMapTapEmpty)

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.also { mv ->
                MapAnalytics.init(mv.context)   // start the non-invasive usage-telemetry flusher (idempotent)
                mv.getMapAsync { map ->
                    holder.map = map
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isCompassEnabled = false   // we render our own compass button
                    // Log the resting camera at the end of a user gesture (non-invasive analytics).
                    val logCamera = {
                        val p = map.cameraPosition
                        p.target?.let { MapAnalytics.move(it.latitude, it.longitude, p.zoom, p.bearing) }
                    }
                    // User drag → stop following so he can look around; log where he panned to.
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) { currentOnUserPan() }
                        override fun onMove(detector: MoveGestureDetector) {}
                        override fun onMoveEnd(detector: MoveGestureDetector) { logCamera() }
                    })
                    // Pinch-zoom end → log the new zoom level too.
                    map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                        override fun onScaleBegin(detector: StandardScaleGestureDetector) {}
                        override fun onScale(detector: StandardScaleGestureDetector) {}
                        override fun onScaleEnd(detector: StandardScaleGestureDetector) { logCamera() }
                    })
                    // Tap a named feature (a Super U, a bus stop, a place label) → offer directions to
                    // it (Google-Maps style). We query the rendered vector tiles under the finger and
                    // pick the nearest NAMED feature; an empty tap dismisses any open card.
                    map.addOnMapClickListener { latLng ->
                        val screen = map.projection.toScreenLocation(latLng)
                        val pad = 24f  // finger-friendly hit box around the tap
                        val box = android.graphics.RectF(
                            screen.x - pad, screen.y - pad, screen.x + pad, screen.y + pad,
                        )
                        val feats = runCatching { map.queryRenderedFeatures(box) }.getOrDefault(emptyList())
                        // Prefer a named POINT (a POI/label pin) under the finger; else any named feature.
                        val hit = feats.firstOrNull { featureName(it) != null && it.geometry() is Point }
                            ?: feats.firstOrNull { featureName(it) != null }
                        val name = hit?.let { featureName(it) }
                        val zoom = map.cameraPosition.zoom
                        if (name != null) {
                            val g = hit.geometry()
                            val lat = if (g is Point) g.latitude() else latLng.latitude
                            val lon = if (g is Point) g.longitude() else latLng.longitude
                            MapAnalytics.tap(lat, lon, "poi", name, zoom)
                            currentOnPoiTap(name, lat, lon)
                            true   // consume — this was a POI tap
                        } else {
                            MapAnalytics.tap(latLng.latitude, latLng.longitude, "empty", null, zoom)
                            currentOnMapTapEmpty()
                            false  // let the map handle a plain tap
                        }
                    }
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
                        // Driving puck: an arrow that points the way (hidden until navigating). Rotation
                        // is MAP-aligned + set to the travel bearing, so it points screen-up in
                        // heading-up mode and the compass direction in north-up. Sits above the dot.
                        style.addImage(IMG_ARROW, arrowBitmap(accent))
                        style.addLayer(
                            SymbolLayer(LYR_ME_ARROW, SRC_ME).withProperties(
                                PropertyFactory.iconImage(IMG_ARROW),
                                PropertyFactory.iconSize(0.8f),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true),
                                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                                // Stay upright facing the camera even when the map is tilted, so it
                                // reads as a crisp arrow pointing up-screen (not squashed onto the road).
                                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                                PropertyFactory.iconRotate(0f),
                                PropertyFactory.visibility(Property.NONE),
                            ),
                        )
                        holder.render(location, routePoints, follow, navigating, headingUp, bearingDeg)
                    }
                }
            }
        },
        update = { holder.render(location, routePoints, follow, navigating, headingUp, bearingDeg) },
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
    // Last good travel bearing — kept while stopped (bearing is null) so the map doesn't snap north.
    private var lastBearing: Double? = null

    fun render(
        loc: DaemonLocation.Fix?,
        points: List<PolylineCodec.LatLon>,
        follow: Boolean,
        navigating: Boolean,
        headingUp: Boolean,
        bearingDeg: Double?,
    ) {
        val s = style ?: return
        val routeGeom: Geometry =
            if (points.size >= 2) LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
            else EMPTY
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeGeom)
        // SNAP-TO-ROUTE: GPS is ±5-10 m noisy, so while navigating ride the blue line — put the puck
        // on the nearest point ON the route and take its heading from that segment's forward bearing,
        // UNLESS we're genuinely off-route (then show the raw fix and let the reroute logic react).
        val snap = if (navigating && loc != null && points.size >= 2)
            NavGeo.snapToRoute(points, loc.lat, loc.lon)?.takeIf { it.offsetM < SNAP_MAX_M } else null
        val dispLat = snap?.lat ?: loc?.lat
        val dispLon = snap?.lon ?: loc?.lon
        // Heading: the route's forward bearing when snapped; else keep the last real GPS bearing (it
        // goes null at rest, so holding it stops the arrow snapping back to north when stopped).
        when {
            snap != null -> lastBearing = snap.bearingDeg
            bearingDeg != null -> lastBearing = bearingDeg
        }

        val meGeom: Geometry = if (dispLat != null && dispLon != null) Point.fromLngLat(dispLon, dispLat) else EMPTY
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meGeom)

        // Marker STYLE: an arrow that points the way while navigating, a plain dot when idle.
        if (navigating) {
            s.getLayer(LYR_ME_DOT)?.setProperties(PropertyFactory.visibility(Property.NONE))
            s.getLayer(LYR_ME_ARROW)?.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.iconRotate((lastBearing ?: 0.0).toFloat()),
            )
        } else {
            s.getLayer(LYR_ME_DOT)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            s.getLayer(LYR_ME_ARROW)?.setProperties(PropertyFactory.visibility(Property.NONE))
        }

        val m = map ?: return
        if (dispLat != null && dispLon != null && follow) {
            // While NAVIGATING, zoom adapts to speed (close when slow, wide when fast); otherwise
            // keep the initial ~5 km on first center and whatever zoom Mike set afterwards.
            val zoom = if (navigating) {
                val raw = loc?.speedKmh ?: 0.0
                smoothedKmh = if (centeredOnce) smoothedKmh * 0.7 + raw * 0.3 else raw
                NavCamera.zoomForSpeed(smoothedKmh, dispLat, m.height.toDouble())
            } else {
                if (!centeredOnce) INITIAL_ZOOM else m.cameraPosition.zoom
            }
            // Heading-up while navigating: rotate the map so the road ahead points forward (lastBearing
            // is kept above while stopped so it doesn't spin back to north).
            val bearing = if (navigating && headingUp) (lastBearing ?: 0.0) else 0.0
            centeredOnce = true
            // While navigating, push the puck DOWN to ~lower third (more road ahead) via a top padding,
            // but keep it ABOVE the bottom HUD panel. Target y ≈ (height + padTop) / 2 → ~0.62·height
            // here. Centered (no padding) otherwise.
            val padTop = if (navigating) m.height * 0.25 else 0.0
            // Tilt the camera forward for the 3D driver's view (Google-Maps style) while navigating;
            // flat (top-down) when idle.
            val tilt = if (navigating) TILT_DRIVING else 0.0
            val pos = CameraPosition.Builder()
                .target(LatLng(dispLat, dispLon))
                .zoom(zoom)
                .bearing(bearing)
                .tilt(tilt)
                .padding(0.0, padTop, 0.0, 0.0)
                .build()
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

/** The best human name for a tapped vector feature, or null if it's unlabeled (water, generic fill). */
private fun featureName(f: Feature): String? {
    for (key in arrayOf("name", "name:latin", "name:en")) {
        val v = runCatching { f.getStringProperty(key) }.getOrNull()
        if (!v.isNullOrBlank()) return v
    }
    return null
}

private const val SRC_ROUTE = "route-src"
private const val LYR_ROUTE = "route-line"
private const val SRC_ME = "me-src"
private const val LYR_ME_RING = "me-ring"
private const val LYR_ME_DOT = "me-dot"
private const val LYR_ME_ARROW = "me-arrow"
private const val IMG_ARROW = "me-arrow-img"

/** A chevron/navigation arrow pointing UP (0°), [fill]-coloured with a white outline — the driving puck. */
private fun arrowBitmap(fill: Int): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val path = Path().apply {
        moveTo(s * 0.50f, s * 0.10f)   // apex (top)
        lineTo(s * 0.84f, s * 0.88f)   // bottom-right wing
        lineTo(s * 0.50f, s * 0.66f)   // centre notch (gives the chevron look)
        lineTo(s * 0.16f, s * 0.88f)   // bottom-left wing
        close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = s * 0.06f; strokeJoin = Paint.Join.ROUND
    })
    return bmp
}
private const val INITIAL_ZOOM = 13.0    // ~5 km across on open
private const val FIT_PADDING_PX = 110
private const val CAMERA_MS = 700
private const val TILT_DRIVING = 50.0    // camera pitch (°) for the 3D driver's view while navigating
private const val SNAP_MAX_M = 40.0      // snap the puck to the route if within this of the line; else raw GPS

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
