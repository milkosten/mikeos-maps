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
import com.mikeos.maps.net.NearbySearch
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
import org.maplibre.android.style.expressions.Expression
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
    poiResults: List<NearbySearch.Place> = emptyList(),
    onPoiResultTap: (NearbySearch.Place) -> Unit = {},
    ambientPois: List<NearbySearch.Place> = emptyList(),
    onViewportChanged: (south: Double, west: Double, north: Double, east: Double, zoom: Double) -> Unit = { _, _, _, _, _ -> },
    focusPoint: PolylineCodec.LatLon? = null,
    styleUrl: String = "${BuildConfig.BASEMAP_URL}/style.json",
    poiTextScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val accent = MikeAccent.toArgb()
    val green = MikeGreen.toArgb()
    val holder = remember { NavMapHolder() }
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    val currentOnPoiTap by rememberUpdatedState(onPoiTap)
    val currentOnMapTapEmpty by rememberUpdatedState(onMapTapEmpty)
    val currentOnPoiResultTap by rememberUpdatedState(onPoiResultTap)
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)

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
                    // Camera settled → report the visible box so the VM can refresh the ambient POI
                    // overlay (every named OSM business in view). The VM debounces + gates on zoom.
                    map.addOnCameraIdleListener {
                        val b = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
                        if (b != null) {
                            currentOnViewportChanged(
                                b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast,
                                map.cameraPosition.zoom,
                            )
                        }
                    }
                    // Tap a named feature (a Super U, a bus stop, a place label) → offer directions to
                    // it (Google-Maps style). We query the rendered vector tiles under the finger and
                    // pick the nearest NAMED feature; an empty tap dismisses any open card.
                    map.addOnMapClickListener { latLng ->
                        val screen = map.projection.toScreenLocation(latLng)
                        val pad = 24f  // finger-friendly hit box around the tap
                        val box = android.graphics.RectF(
                            screen.x - pad, screen.y - pad, screen.x + pad, screen.y + pad,
                        )
                        // A tap on one of our Explore result pins → route to that place (checked first).
                        val poiHit = runCatching { map.queryRenderedFeatures(box, LYR_POIS_DOT) }
                            .getOrDefault(emptyList()).firstOrNull()
                        if (poiHit != null) {
                            val idx = runCatching { poiHit.getNumberProperty("idx")?.toInt() }.getOrNull()
                            val place = idx?.let { holder.pois.getOrNull(it) }
                            if (place != null) {
                                currentOnPoiResultTap(place)
                                return@addOnMapClickListener true
                            }
                        }
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
                    map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                        holder.style = style
                        holder.currentStyleUrl = styleUrl
                        installOverlays(style, accent)
                        holder.render(location, routePoints, follow, navigating, headingUp, bearingDeg, poiResults, ambientPois, focusPoint, poiTextScale)
                    }
                }
            }
        },
        update = {
            // Theme switch (auto light/dark): swap the basemap style, then RE-ADD our overlays — a
            // MapLibre setStyle drops all custom sources/layers — and re-push the current data. Camera
            // is preserved by MapLibre across the swap.
            val m = holder.map
            if (m != null && holder.style != null && holder.currentStyleUrl != null && holder.currentStyleUrl != styleUrl) {
                holder.currentStyleUrl = styleUrl
                m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    holder.style = style
                    installOverlays(style, accent)
                    holder.render(location, routePoints, follow, navigating, headingUp, bearingDeg, poiResults, ambientPois, focusPoint, poiTextScale)
                }
            } else {
                holder.render(location, routePoints, follow, navigating, headingUp, bearingDeg, poiResults, ambientPois, focusPoint, poiTextScale)
            }
        },
    )
}

/**
 * Add MikeMaps' overlay sources + layers onto a freshly-loaded [style] (route line, Explore POI pins,
 * the location puck ring/dot, and the driving arrow). Called on the initial style load AND after every
 * theme swap, since MapLibre's setStyle wipes custom layers.
 */
private fun installOverlays(style: Style, accent: Int) {
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
    // Trip-planner destination marker (the place you're planning a trip to).
    style.addSource(GeoJsonSource(SRC_FOCUS))
    style.addLayer(
        CircleLayer(LYR_FOCUS, SRC_FOCUS).withProperties(
            PropertyFactory.circleColor(accent),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Ambient viewport POIs — every named OSM/SIRENE business in view (bakeries, pharmacies,
    // hairdressers…), drawn UNDER the Explore pins so the map feels full while browsing. Each renders a
    // recognizable CATEGORY EMOJI (🍴 restaurant, 🍺 bar, ✂️ hairdresser, 🛒 supermarket…) instead of an
    // anonymous colour dot — emoji don't live in the basemap's SDF glyph font, so we pre-render each to a
    // bitmap and register it as an icon image (below), keyed by the emoji string in the feature's `icon`.
    // Tappable via the same name-feature tap path → the place-details card.
    NearbySearch.ALL_ICONS.forEach { e -> style.addImage(emojiImageId(e), emojiBitmap(e)) }
    style.addSource(GeoJsonSource(SRC_AMBIENT))
    style.addLayer(
        SymbolLayer(LYR_AMBIENT_LABEL, SRC_AMBIENT).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconSize(AMBIENT_ICON_SIZE),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconOptional(false),
            PropertyFactory.iconPadding(2f),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(AMBIENT_LABEL_SIZE),
            PropertyFactory.textColor(poiTextColor()),
            PropertyFactory.textHaloWidth(0f),   // ONE solid colour — no outline
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textMaxWidth(7f),
        ),
    )
    // Explore result pins (parking/fuel/food…): a category-coloured dot + name label, tappable.
    style.addSource(GeoJsonSource(SRC_POIS))
    style.addLayer(
        CircleLayer(LYR_POIS_DOT, SRC_POIS).withProperties(
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("cat"),
                    Expression.literal("PARKING"), Expression.rgb(76, 141, 255),
                    Expression.literal("FUEL"), Expression.rgb(255, 152, 0),
                    Expression.literal("CHARGING"), Expression.rgb(61, 220, 132),
                    Expression.literal("FOOD"), Expression.rgb(255, 90, 122),
                    Expression.literal("SHOP"), Expression.rgb(176, 124, 255),
                    Expression.literal("REST"), Expression.rgb(38, 198, 218),
                    Expression.literal("CASH"), Expression.rgb(255, 194, 75),
                    Expression.rgb(154, 165, 177),   // default (OTHER)
                ),
            ),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        SymbolLayer(LYR_POIS_LABEL, SRC_POIS).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            // MUST use a font the basemap's glyphs provide (Noto Sans), or the glyph fetch fails and
            // kills rendering of the whole source (dots included).
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(poiTextColor()),
            PropertyFactory.textHaloWidth(0f),   // ONE solid colour — no outline
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false),
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
    // Driving puck: an arrow that points the way (hidden until navigating). MAP-aligned rotation set to
    // the travel bearing; viewport pitch-alignment keeps it crisp when the map is tilted. Above the dot.
    style.addImage(IMG_ARROW, arrowBitmap(accent))
    style.addLayer(
        SymbolLayer(LYR_ME_ARROW, SRC_ME).withProperties(
            PropertyFactory.iconImage(IMG_ARROW),
            PropertyFactory.iconSize(0.8f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotate(0f),
            PropertyFactory.visibility(Property.NONE),
        ),
    )
}

/** Pushes location/route into the style's sources and drives the follow camera. */
private class NavMapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var currentStyleUrl: String? = null   // the basemap style currently loaded (for theme switching)
    var pois: List<NearbySearch.Place> = emptyList()   // current result pins (for tap → Place lookup)
    private var centeredOnce = false
    private var lastFittedRoute: List<PolylineCodec.LatLon>? = null
    private var lastFocusLat: Double? = null   // last destination we flew to (avoid re-flying every frame)
    private var lastFocusLon: Double? = null
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
        poiResults: List<NearbySearch.Place>,
        ambientPois: List<NearbySearch.Place>,
        focusPoint: PolylineCodec.LatLon?,
        poiTextScale: Float = 1f,
    ) {
        val s = style ?: return
        // Accessibility: scale the business-name labels (and, gently, their emoji chips) to the user's
        // chosen text size. Applied every render so a settings change takes effect live.
        s.getLayer(LYR_AMBIENT_LABEL)?.setProperties(
            PropertyFactory.textSize(AMBIENT_LABEL_SIZE * poiTextScale),
            PropertyFactory.iconSize(AMBIENT_ICON_SIZE * (1f + (poiTextScale - 1f) * 0.5f)),
        )
        // Trip-planner destination marker (a pin the map flies to before a route exists).
        s.getSourceAs<GeoJsonSource>(SRC_FOCUS)?.setGeoJson(
            if (focusPoint != null) Point.fromLngLat(focusPoint.lon, focusPoint.lat) as Geometry else EMPTY
        )
        val routeGeom: Geometry =
            if (points.size >= 2) LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
            else EMPTY
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeGeom)

        // Explore result pins (kept here so a map tap can look the tapped pin's Place back up). Built
        // as a raw GeoJSON string — the object-API FeatureCollection silently failed to render here.
        pois = poiResults
        val json = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        poiResults.forEachIndexed { i, p ->
            if (i > 0) json.append(',')
            val nm = p.name.replace("\\", "\\\\").replace("\"", "\\\"")
            json.append("{\"type\":\"Feature\",\"properties\":{\"idx\":$i,\"cat\":\"${p.category.name}\",\"col\":\"${NearbySearch.sectorOf(p.icon)}\",\"name\":\"$nm\"},")
                .append("\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lon},${p.lat}]}}")
        }
        json.append("]}")
        s.getSourceAs<GeoJsonSource>(SRC_POIS)?.setGeoJson(json.toString())

        // Ambient viewport POIs (the "make it full" overlay) — same raw-GeoJSON shape; name drives both
        // the label and the tap → details lookup.
        val aj = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        ambientPois.forEachIndexed { i, p ->
            if (i > 0) aj.append(',')
            val nm = p.name.replace("\\", "\\\\").replace("\"", "\\\"")
            aj.append("{\"type\":\"Feature\",\"properties\":{\"cat\":\"${p.category.name}\",\"col\":\"${NearbySearch.sectorOf(p.icon)}\",\"icon\":\"${emojiImageId(p.icon)}\",\"name\":\"$nm\"},")
                .append("\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lon},${p.lat}]}}")
        }
        aj.append("]}")
        s.getSourceAs<GeoJsonSource>(SRC_AMBIENT)?.setGeoJson(aj.toString())
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
        } else if (!follow && points.size < 2 && focusPoint != null &&
            (focusPoint.lat != lastFocusLat || focusPoint.lon != lastFocusLon)) {
            // A destination was chosen but there's no route yet — fly to the place (Travel-preview).
            runCatching { m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focusPoint.lat, focusPoint.lon), FOCUS_ZOOM)) }
            lastFocusLat = focusPoint.lat; lastFocusLon = focusPoint.lon
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

/** Stable style-image id for a category [emoji] (the ambient features carry this in their `icon` prop). */
private fun emojiImageId(emoji: String): String = "poi-$emoji"

/**
 * Sector → LABEL TEXT colour (keys are [NearbySearch.sectorOf]), so food, money, health, retail,
 * lodging… each read at a glance. Colours are saturated but dark enough to stay legible on the LIGHT
 * basemap; paired with a white text halo they're also crisp on the dark basemap (white-on-white was
 * unreadable before). The feature's `col` property carries the sector key.
 */
private fun poiTextColor(): Expression = Expression.match(
    Expression.get("col"),
    Expression.literal("EAT"), Expression.color(android.graphics.Color.parseColor("#D9480F")),    // food & drink — orange-red
    Expression.literal("MONEY"), Expression.color(android.graphics.Color.parseColor("#C2255C")),  // banks — magenta
    Expression.literal("HEALTH"), Expression.color(android.graphics.Color.parseColor("#0C8599")), // health — teal
    Expression.literal("SHOP"), Expression.color(android.graphics.Color.parseColor("#6741D9")),   // retail — violet
    Expression.literal("STAY"), Expression.color(android.graphics.Color.parseColor("#2B8A3E")),   // lodging/sights — green
    Expression.literal("AUTO"), Expression.color(android.graphics.Color.parseColor("#E67700")),   // fuel/repair — amber
    Expression.literal("CIVIC"), Expression.color(android.graphics.Color.parseColor("#1565C0")),  // civic/leisure — blue
    Expression.color(android.graphics.Color.parseColor("#5F6B7A")),                               // OTHER — slate grey
)

/**
 * Render a category [emoji] to a small bitmap so MapLibre GL Native can use it as an icon image — the
 * basemap's SDF glyph font has no colour-emoji glyphs, so text-field emoji would render as tofu. Drawn
 * on a soft white chip (with a hairline ring) so it stays legible on any basemap colour. Android's
 * default typeface renders emoji in full colour via NotoColorEmoji.
 */
private fun emojiBitmap(emoji: String): Bitmap {
    val size = 68
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val r = size * 0.40f
    val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(4f, 0f, 1.5f, 0x40000000)
    }
    c.drawCircle(cx, cy, r, chip)
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000; style = Paint.Style.STROKE; strokeWidth = 1.5f
    })
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.46f
        textAlign = Paint.Align.CENTER
    }
    val fm = tp.fontMetrics
    c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    return bmp
}

private const val SRC_ROUTE = "route-src"
private const val LYR_ROUTE = "route-line"
private const val SRC_POIS = "pois-src"
private const val LYR_POIS_DOT = "pois-dot"
private const val LYR_POIS_LABEL = "pois-label"
private const val SRC_AMBIENT = "ambient-src"
private const val LYR_AMBIENT_LABEL = "ambient-label"
private const val AMBIENT_ICON_SIZE = 0.62f    // emoji chip scale for the ambient business overlay
private const val AMBIENT_LABEL_SIZE = 11f      // business-name text size (raised by the a11y setting)
private const val SRC_FOCUS = "focus-src"
private const val LYR_FOCUS = "focus-dot"
private const val FOCUS_ZOOM = 15.5   // zoom when flying to a chosen trip destination
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
