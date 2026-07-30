package com.mikeos.maps

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.maps.agent.MapsMikeAgent
import com.mikeos.maps.nav.Guidance
import com.mikeos.maps.nav.ManeuverKind
import com.mikeos.maps.nav.NavFormat
import com.mikeos.maps.nav.NavInfo
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.NearbySearch
import com.mikeos.maps.net.TripsCloudClient
import com.mikeos.maps.trips.TripManager
import com.mikeos.maps.ui.NavigationMap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.mikeos.maps.nav.NavGeo
import com.mikeos.maps.ui.theme.MikeAccent
import com.mikeos.maps.ui.theme.MikeBg
import com.mikeos.maps.ui.theme.MikeGreen
import com.mikeos.maps.ui.theme.MikeMuted
import com.mikeos.maps.ui.theme.MikeOnSurface
import com.mikeos.maps.ui.theme.MikeOsTheme
import com.mikeos.maps.ui.theme.MikeRed
import com.mikeos.maps.ui.theme.MikeSurface
import com.mikeos.maps.ui.theme.MikeSurfaceVariant
import kotlin.math.roundToInt

/** A destination handed to MikeMaps by another app (MikeShopping "Directions", or a geo: link). */
data class DestReq(val name: String, val lat: Double, val lon: Double)

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // MikeStreet dashboard capture (opt-in). CAMERA is requested only when the user switches it on.
    private lateinit var streetCapture: com.mikeos.maps.street.StreetCapture
    // Ambient-light sensor → auto light/dark map (only active while foreground).
    private lateinit var lightSensor: com.mikeos.maps.ui.LightSensor
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) streetCapture.refresh() }

    private fun onStreetToggle(on: Boolean) {
        com.mikeos.maps.street.MikeStreet.setEnabled(this, on)
        if (on && !streetCapture.hasCameraPermission()) {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        } else {
            streetCapture.refresh()
        }
    }

    // Inbound deep-link destination (extras or geo:), consumed once by the composable.
    private val pendingDest = androidx.compose.runtime.mutableStateOf<DestReq?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Never let the screen time out while MikeMaps is up — you're navigating.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions()
        // Embed the shared MikeAgent runtime (soul + nav skills + heartbeat + live hive).
        MapsMikeAgent.install(this)
        // MikeStreet capture (P1) — opt-in dashboard imagery.
        com.mikeos.maps.street.MikeStreet.init(this)
        streetCapture = com.mikeos.maps.street.StreetCapture(this)
        // Durable upload drain: a periodic WorkManager safety-net so stranded drives always reach the
        // lake even if the app is killed right after a drive (survives death/Doze, retries with backoff).
        com.mikeos.maps.street.StreetUploader.enqueuePeriodic(this)
        // Map appearance + ambient-light auto light/dark.
        com.mikeos.maps.ui.MapTheme.init(this)
        com.mikeos.maps.ui.TextSize.init(this)
        lightSensor = com.mikeos.maps.ui.LightSensor(this)
        // One-time: clean the region/country tail off old saved/history place labels.
        lifecycleScope.launch {
            runCatching { com.mikeos.maps.data.PlacesRepo.migrateLabels(this@MainActivity) }
        }
        pendingDest.value = parseDest(intent)

        setContent {
            MikeOsTheme {
                val vm: MapsViewModel = viewModel()
                MapFirstScreen(vm, onStreetToggle = ::onStreetToggle)
                val dest = pendingDest.value
                androidx.compose.runtime.LaunchedEffect(dest) {
                    if (dest != null) {
                        vm.navigateTo(dest.name, dest.lat, dest.lon)
                        pendingDest.value = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDest(intent)?.let { pendingDest.value = it }
    }

    /** Parse a destination from explicit extras (dest_lat/dest_lon/dest_name) or a `geo:` URI. */
    private fun parseDest(intent: Intent?): DestReq? {
        intent ?: return null
        if (intent.hasExtra("dest_lat") && intent.hasExtra("dest_lon")) {
            val lat = intent.getDoubleExtra("dest_lat", Double.NaN)
            val lon = intent.getDoubleExtra("dest_lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                return DestReq(intent.getStringExtra("dest_name") ?: "Destination", lat, lon)
            }
        }
        val data = intent.data
        if (data != null && data.scheme == "geo") {
            // geo:lat,lon  or  geo:0,0?q=lat,lon(Name)
            val ssp = data.schemeSpecificPart ?: return null
            val coordPart = ssp.substringBefore("?")
            var lat = coordPart.substringBefore(",").toDoubleOrNull()
            var lon = coordPart.substringAfter(",", "").substringBefore("?").toDoubleOrNull()
            var name = "Destination"
            val q = data.query?.substringAfter("q=", "") ?: ""
            if (q.isNotBlank()) {
                val qCoords = q.substringBefore("(")
                qCoords.substringBefore(",").toDoubleOrNull()?.let { la ->
                    qCoords.substringAfter(",", "").toDoubleOrNull()?.let { lo -> lat = la; lon = lo }
                }
                if (q.contains("(")) name = q.substringAfter("(").substringBefore(")")
            }
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                return DestReq(name, lat!!, lon!!)
            }
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        HeartbeatService.start(this)
    }

    override fun onStop() {
        super.onStop()
        HeartbeatService.stop(this)
    }

    override fun onResume() {
        super.onResume()
        if (::streetCapture.isInitialized) streetCapture.onResume()
        if (::lightSensor.isInitialized) lightSensor.start()
    }

    override fun onPause() {
        super.onPause()
        if (::streetCapture.isInitialized) streetCapture.onPause()
        if (::lightSensor.isInitialized) lightSensor.stop()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapFirstScreen(vm: MapsViewModel, onStreetToggle: (Boolean) -> Unit = {}) {
    val streetEnabled by com.mikeos.maps.street.MikeStreet.enabled.collectAsStateWithLifecycle()
    val streetCapturing by com.mikeos.maps.street.MikeStreet.capturing.collectAsStateWithLifecycle()
    val streetFrames by com.mikeos.maps.street.MikeStreet.frameCount.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val navInfo by vm.navInfo.collectAsStateWithLifecycle()
    val speedLimit by vm.speedLimit.collectAsStateWithLifecycle()
    val guidance by vm.guidance.collectAsStateWithLifecycle()
    val ambientPois by vm.ambientPois.collectAsStateWithLifecycle()
    val mapStyleUrl by com.mikeos.maps.ui.MapTheme.styleUrl.collectAsStateWithLifecycle()
    val mapThemeMode by com.mikeos.maps.ui.MapTheme.mode.collectAsStateWithLifecycle()
    val textSizeLevel by com.mikeos.maps.ui.TextSize.level.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var follow by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    // Orientation: heading-up (course-up) is the default while driving; tap the compass for north-up.
    var northUp by remember { mutableStateOf(false) }

    // Poll the daemon fix while the map is on screen (moving dot + prefetch + HUD).
    DisposableEffect(Unit) {
        vm.startLiveLocation()
        onDispose { vm.stopLiveLocation() }
    }

    // Follow the dot while driving/idle; turn follow OFF while previewing or trip-planning so the map
    // frames the route / flies to the chosen destination instead of chasing the puck.
    LaunchedEffect(state.previewing, active != null, state.planScreen) {
        follow = active != null || (!state.previewing && state.planScreen == PlanScreen.NONE)
    }

    Box(Modifier.fillMaxSize().background(MikeBg)) {

        // THE MAP — the whole screen. Map-first.
        NavigationMap(
            location = location,
            routePoints = state.routePoints,
            follow = follow,
            navigating = active != null,
            headingUp = !northUp,
            bearingDeg = location?.bearing,
            onUserPan = { follow = false },
            onPoiTap = { name, lat, lon -> vm.onMapPoiTapped(name, lat, lon) },
            onMapTapEmpty = { vm.dismissTappedPlace() },
            poiResults = state.nearby,
            onPoiResultTap = { vm.chooseNearby(it) },
            ambientPois = ambientPois,
            onViewportChanged = { s, w, n, e, z -> vm.onViewport(s, w, n, e, z) },
            focusPoint = state.planDest?.let { com.mikeos.maps.net.PolylineCodec.LatLon(it.lat, it.lon) },
            styleUrl = mapStyleUrl,
            poiTextScale = textSizeLevel.scale,
            modifier = Modifier.fillMaxSize(),
        )

        // Right-side controls, by the compass: 🔍 Explore-nearby (parking/fuel/EV around the
        // destination or you) stacked above the compass.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircleButton(onClick = { vm.exploreNearby() }) {
                Icon(Icons.Filled.Search, contentDescription = "Search nearby (parking, fuel, EV)", tint = MikeAccent)
            }
            Spacer(Modifier.height(12.dp))
            // Compass — tap to toggle heading-up (default while driving) ↔ true-north. The red needle
            // points to real north (rotates opposite the map's bearing).
            CompassButton(
                appliedBearing = if (active != null && !northUp) (location?.bearing ?: 0.0) else 0.0,
                northUp = northUp,
                onClick = { northUp = !northUp },
            )
        }

        // (Speed-limit sign now lives just above the HUD — see the bottom overlay — so it's never
        // covered by the panel, and the current speed is shown ONCE, in the HUD, coloured by over-limit.)

        // Top overlay: the turn-by-turn banner while navigating, else the ☰ menu + agent window.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val g = guidance
            if (active != null && g != null) {
                ManeuverBanner(g)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircleButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MikeOnSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    // ★ saved places — a dedicated, searchable screen (scales to thousands).
                    CircleButton(onClick = { vm.loadFavorites(); savedOpen = true }) {
                        Icon(Icons.Filled.Star, contentDescription = "Saved places", tint = MikeAccent)
                    }
                    Spacer(Modifier.size(10.dp))
                    com.mikeos.core.ui.AgentIconButton(
                        onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
                    )
                }
            }
        }

        // MikeStreet: a small REC pill while it's capturing dashboard frames.
        if (streetCapturing) {
            StreetRecPill(
                frames = streetFrames,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
            )
        }

        // Bottom overlay: driving HUD while navigating, else a slim "Where to?" bar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Recenter button appears (just above the panel) once Mike has panned away.
            if (!follow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CircleButton(onClick = { follow = true }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = "Recenter", tint = MikeAccent)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Status notices only when NOT navigating — no clutter on the driving screen.
            if (active == null) {
                state.notice?.let {
                    NoticePill(it)
                    Spacer(Modifier.height(10.dp))
                }
            }

            val a = active
            // Speed-limit sign floats just ABOVE the HUD, left-aligned — always fully visible (never
            // under the panel), shown only while navigating with a known posted limit.
            if (a != null && speedLimit != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    SpeedLimitSign(speedLimit!!)
                }
                Spacer(Modifier.height(10.dp))
            }
            when {
                a != null -> DrivingHud(a, navInfo, busy = state.busy, speedLimit = speedLimit, onEnd = { vm.endTrip() })
                state.planScreen == PlanScreen.PLANNER -> TripPlannerPanel(
                    state = state, busy = state.busy,
                    onEditOrigin = { vm.openOriginSearch() },
                    onUseMyPosition = { vm.useMyPosition() },
                    onEditDest = { vm.openSearch() },
                    onStart = { vm.startPreviewed() },
                    onClose = { vm.closePlan() },
                )
                state.planScreen == PlanScreen.DEST_PREVIEW -> DestPreviewCard(
                    dest = state.planDest!!, busy = state.busy,
                    onTravel = { vm.beginTravel() }, onDismiss = { vm.closePlan() },
                )
                state.previewing -> RoutePreviewPanel(
                    state = state,
                    busy = state.busy,
                    onStart = { vm.startPreviewed() },
                    onCancel = { vm.cancelPreview() },
                )
                state.tappedPlace != null -> TappedPlaceCard(
                    place = state.tappedPlace!!,
                    details = state.tappedDetails,
                    busy = state.busy,
                    onDirections = { vm.directionsToTappedPlace() },
                    onDismiss = { vm.dismissTappedPlace() },
                )
                else -> WhereToBar(location, onClick = { vm.openSearch() })
            }
        }

        // Full-screen destination/origin search — input at top, results above the keyboard. On top.
        if (state.planScreen == PlanScreen.SEARCH) {
            DestinationSearchScreen(
                state = state,
                onQueryChange = vm::onQueryChange,
                onPick = { vm.pickPlanResult(it) },
                onBack = { vm.backFromSearch() },
                nearLat = location?.lat, nearLon = location?.lon,
            )
        }
    }

    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MikeSurface,
        ) {
            MenuSheet(
                state = state,
                onQueryChange = vm::onQueryChange,
                // Search shows a choosable list — it does NOT route; the menu stays open.
                onSearch = { vm.search() },
                onResume = { name ->
                    vm.previewDestination(name)
                    menuOpen = false
                },
                onChoose = { s ->
                    vm.chooseSuggestion(s)
                    menuOpen = false
                },
                onToggleFavorite = { label, lat, lon -> vm.toggleFavorite(label, lat, lon) },
                streetEnabled = streetEnabled,
                onStreetToggle = onStreetToggle,
                mapThemeMode = mapThemeMode,
                onMapThemeChange = { com.mikeos.maps.ui.MapTheme.setMode(context, it) },
                textSizeLevel = textSizeLevel,
                onTextSizeChange = { com.mikeos.maps.ui.TextSize.setLevel(context, it) },
                nearLat = location?.lat,
                nearLon = location?.lon,
            )
        }
    }

    if (savedOpen) {
        ModalBottomSheet(
            onDismissRequest = { savedOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MikeSurface,
        ) {
            SavedPlacesSheet(
                favorites = state.favorites,
                onChoose = { p ->
                    vm.chooseSuggestion(Suggestion(p.label, p.lat, p.lon, fromHistory = false))
                    savedOpen = false
                },
                onRemove = { p -> vm.toggleFavorite(p.label, p.lat, p.lon) },
            )
        }
    }

    if (state.nearbyOpen) {
        ModalBottomSheet(
            onDismissRequest = { vm.dismissNearby() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MikeSurface,
        ) {
            NearbyPlacesSheet(
                anchor = state.nearbyAnchor,
                busy = state.nearbyBusy,
                places = state.nearby,
                mode = state.nearbyMode,
                hasRoute = state.nearbyHasRoute,
                onMode = { m -> vm.loadNearby(m) },
                onChoose = { p -> vm.chooseNearby(p) },
            )
        }
    }
}

// ---- MikeStreet REC indicator --------------------------------------------------------------

@Composable
private fun StreetRecPill(frames: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MikeSurface.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(MikeRed))
        Spacer(Modifier.width(8.dp))
        Text("MikeStreet · $frames", color = MikeOnSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---- Explore nearby (parking / fuel / EV) --------------------------------------------------

@Composable
private fun NearbyPlacesSheet(
    anchor: String?,
    busy: Boolean,
    places: List<NearbySearch.Place>,
    mode: String,
    hasRoute: Boolean,
    onMode: (String) -> Unit,
    onChoose: (NearbySearch.Place) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        val title = when {
            mode == "route" -> "ALONG YOUR ROUTE"
            anchor == null || anchor == "you" -> "NEAR YOU"
            else -> "NEAR ${anchor.uppercase()}"
        }
        Text(title, color = MikeAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        // With a route, offer the At-destination / Along-route toggle; else a one-line hint.
        if (hasRoute) {
            Row(Modifier.fillMaxWidth()) {
                listOf("dest" to "At destination", "route" to "Along route").forEach { (m, label) ->
                    val sel = mode == m
                    Text(
                        label,
                        modifier = Modifier.weight(1f).clickable { onMode(m) }.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (sel) MikeAccent else MikeMuted,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Text("Parking, fuel, food & more nearby — tap to route there", color = MikeMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MikeAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Searching…", color = MikeMuted, fontSize = 13.sp)
            }
            places.isEmpty() -> Text("Nothing found nearby.", color = MikeMuted, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(places, key = { "${it.name}|${it.lat}|${it.lon}" }) { p ->
                    NearbyRow(p, onClick = { onChoose(p) })
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(p: NearbySearch.Place, onClick: () -> Unit) {
    val icon = when (p.category) {
        NearbySearch.Category.PARKING -> Icons.Filled.LocalParking
        NearbySearch.Category.FUEL -> Icons.Filled.LocalGasStation
        NearbySearch.Category.CHARGING -> Icons.Filled.EvStation
        NearbySearch.Category.FOOD -> Icons.Filled.Restaurant
        NearbySearch.Category.SHOP -> Icons.Filled.ShoppingCart
        NearbySearch.Category.REST -> Icons.Filled.Wc
        NearbySearch.Category.CASH -> Icons.Filled.LocalAtm
        NearbySearch.Category.OTHER -> Icons.Filled.Place
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MikeAccent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Along-route results show drive-past distance ("in 3.2 km"); point results show straight-line.
            val distLabel = p.alongM?.let { if (it >= 1000) "in ${"%.1f".format(it / 1000.0)} km" else "in $it m" }
                ?: "${p.distanceM} m"
            val sub = listOfNotNull(distLabel, p.detail).joinToString(" · ")
            Text(sub, color = MikeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.Navigation, contentDescription = "Route here", tint = MikeAccent)
    }
}

// ---- Turn-by-turn banner -------------------------------------------------------------------

@Composable
private fun ManeuverBanner(g: Guidance) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MikeAccent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(maneuverIcon(g.kind), contentDescription = null, tint = MikeBg, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    NavFormat.distance(g.distanceM / 1000.0),
                    color = MikeBg, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
                Text(g.instruction, color = MikeBg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            }
        }
    }
}

private fun maneuverIcon(kind: ManeuverKind): ImageVector = when (kind) {
    ManeuverKind.DEPART -> Icons.Filled.Navigation
    ManeuverKind.ARRIVE -> Icons.Filled.Flag
    ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT -> Icons.Filled.TurnLeft
    ManeuverKind.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT -> Icons.Filled.TurnRight
    ManeuverKind.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverKind.UTURN -> Icons.Filled.UTurnLeft
    ManeuverKind.STRAIGHT, ManeuverKind.MERGE, ManeuverKind.FORK, ManeuverKind.ROUNDABOUT -> Icons.Filled.Straight
}

// ---- Bottom panels -------------------------------------------------------------------------

@Composable
private fun DrivingHud(
    a: TripManager.ActiveTrip,
    navInfo: NavInfo?,
    busy: Boolean,
    speedLimit: Int?,
    onEnd: () -> Unit,
) {
    val speed = navInfo?.speedKmh ?: a.lastSpeedKmh ?: 0.0
    val speedColor = speedColorFor(speed, speedLimit)   // blue when legal, red (redder) when over
    val remKm = navInfo?.remainingKm ?: a.km
    val remMin = navInfo?.remainingMin ?: a.etaMin
    val eta = navInfo?.etaClock ?: NavFormat.eta(a.etaMin)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MikeSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // No status text at all (map-first) — just the End control, then the live metrics.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onEnd,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeRed, contentColor = MikeOnSurface),
                ) { Text("End", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("${speed.roundToInt()}", "km/h", hero = true, heroColor = speedColor)
                Metric(NavFormat.distance(remKm), "to go")
                Metric(NavFormat.duration(remMin), "drive")
                Metric(eta, "arrival")
            }
        }
    }
}

@Composable
private fun RoutePreviewPanel(
    state: MapsState,
    busy: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MikeSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("ROUTE", color = MikeAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(state.destName ?: "Destination", color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                state.routeKm?.let { Metric(NavFormat.distance(it), "distance") }
                state.routeEtaMin?.let { Metric(NavFormat.duration(it), "drive") }
                state.routeEtaMin?.let { Metric(NavFormat.eta(it), "arrival") }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeSurfaceVariant, contentColor = MikeOnSurface),
                ) { Text("Cancel") }
                Spacer(Modifier.size(10.dp))
                Button(
                    onClick = onStart,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = MikeBg, strokeWidth = 2.dp)
                    else Text("Start", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Google-Maps-style card for a POI tapped on the map: its details (category, hours, phone, website) + Directions. */
@Composable
private fun TappedPlaceCard(
    place: TappedPoi,
    details: com.mikeos.maps.net.PoiDetails?,
    busy: Boolean,
    onDirections: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MikeSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = MikeAccent)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(details?.category ?: "PLACE", color = MikeAccent, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(place.name, color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MikeMuted)
                }
            }

            // Opening hours → a coloured live-status pill + today's hours + an expandable full week.
            details?.openingHours?.let { oh ->
                Spacer(Modifier.height(12.dp))
                OpeningHoursBlock(oh, fromWeb = details.hoursFromWeb)
            }
            // Phone (tap to call) + Website (tap to open).
            details?.phone?.let { ph ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${ph.replace(" ", "")}"))) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("☎", fontSize = 15.sp); Spacer(Modifier.width(10.dp))
                    Text(ph, color = MikeAccent, fontSize = 14.sp)
                }
            }
            details?.website?.let { web ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web))) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🌐", fontSize = 15.sp); Spacer(Modifier.width(10.dp))
                    Text(web.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                        color = MikeAccent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onDirections,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = MikeBg, strokeWidth = 2.dp)
                else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Directions", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * The opening-hours block in a place card: a coloured live-status pill ("Open · closes 20:00",
 * "Closed for lunch · opens 14:00", "Closed · opens tomorrow 08:30" — evaluated in CET), then today's
 * hours, expandable to the full week with today highlighted. Falls back to the raw string if unparsed.
 */
@Composable
private fun OpeningHoursBlock(oh: String, fromWeb: Boolean = false) {
    val week = remember(oh) { com.mikeos.maps.nav.OpeningHours.parse(oh) }
    if (week == null) {   // exotic syntax we don't parse → just show the raw hours
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🕑", fontSize = 14.sp); Spacer(Modifier.width(8.dp))
            Text(oh, color = MikeMuted, fontSize = 13.sp)
        }
        return
    }
    val st = remember(oh) { com.mikeos.maps.nav.OpeningHours.status(week) }
    val todayIdx = remember(oh) { com.mikeos.maps.nav.OpeningHours.todayIndex() }
    var expanded by remember(oh) { mutableStateOf(false) }
    val amber = Color(0xFFD9820C)
    val pill = when (st.state) {
        com.mikeos.maps.nav.OpeningHours.State.OPEN -> MikeGreen
        com.mikeos.maps.nav.OpeningHours.State.CLOSESOON,
        com.mikeos.maps.nav.OpeningHours.State.OPENSOON,
        com.mikeos.maps.nav.OpeningHours.State.LUNCH -> amber
        com.mikeos.maps.nav.OpeningHours.State.CLOSED -> MikeRed
        com.mikeos.maps.nav.OpeningHours.State.UNKNOWN -> MikeMuted
    }

    Column(Modifier.fillMaxWidth()) {
        // Status pill (+ a subtle "from their website" note when the hours came from the crawl).
        if (st.label.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(pill.copy(alpha = 0.15f))
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(pill))
                    Spacer(Modifier.width(7.dp))
                    Text(st.label, color = pill, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (fromWeb) {
                    Spacer(Modifier.width(8.dp))
                    Text("from their website", color = MikeMuted, fontSize = 11.sp)
                }
            }
        }
        // Today row — tap to expand the week.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(com.mikeos.maps.nav.OpeningHours.DAY_FULL[todayIdx], color = MikeOnSurface,
                fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(com.mikeos.maps.nav.OpeningHours.dayLabel(week, todayIdx), color = MikeMuted,
                fontSize = 13.5.sp, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Hide week" else "See full week", tint = MikeMuted,
            )
        }
        // Full week, today highlighted.
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                for (i in 0..6) {
                    val today = i == todayIdx
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(com.mikeos.maps.nav.OpeningHours.DAY_LABEL[i],
                            color = if (today) MikeOnSurface else MikeMuted,
                            fontSize = 13.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(44.dp))
                        Text(com.mikeos.maps.nav.OpeningHours.dayLabel(week, i),
                            color = if (today) MikeOnSurface else MikeMuted,
                            fontSize = 13.sp, fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhereToBar(location: DaemonLocation.Fix?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MikeSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MikeAccent)
        Spacer(Modifier.size(10.dp))
        Text("Where to?", color = MikeMuted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        val kmh = location?.speedKmh
        if (kmh != null && kmh >= 2.0) {
            Text("${kmh.roundToInt()} km/h", color = MikeOnSurface, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun NoticePill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MikeSurfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = MikeOnSurface, fontSize = 13.sp)
    }
}

// ---- Menu / search sheet -------------------------------------------------------------------

@Composable
private fun DestinationSearchScreen(
    state: MapsState,
    onQueryChange: (String) -> Unit,
    onPick: (Suggestion) -> Unit,
    onBack: () -> Unit,
    nearLat: Double?,
    nearLon: Double?,
) {
    // Full-screen search: input pinned at TOP, results fill the middle and sit right above the
    // keyboard (imePadding). Field owns its text (no state.query feedback loop — see MenuSheet note).
    var field by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.searchingOrigin) { runCatching { focusRequester.requestFocus() } }
    val hint = if (state.searchingOrigin) "Start from…" else "Where to?"
    Column(Modifier.fillMaxSize().background(MikeBg).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MikeOnSurface) }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; onQueryChange(it.text) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(hint, color = MikeMuted) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue(""); onQueryChange("") }) {
                        Icon(Icons.Filled.Close, "Clear", tint = MikeMuted)
                    }
                },
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            items(state.suggestions) { s ->
                SuggestionRow(
                    s = s, saved = false, nearLat = nearLat, nearLon = nearLon,
                    onClick = { onPick(s) }, onToggleSave = {},
                )
            }
            if (state.suggestions.isEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    when {
                        state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = MikeAccent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp)); Text("Searching…", color = MikeMuted, fontSize = 14.sp)
                        }
                        state.query.isNotBlank() -> Text(state.notice ?: "No places found.", color = MikeMuted, fontSize = 14.sp)
                        else -> Text("Type an address or place.", color = MikeMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DestPreviewCard(dest: PlacePoint, busy: Boolean, onTravel: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, null, tint = MikeAccent)
                Spacer(Modifier.width(10.dp))
                Text(dest.name, color = MikeOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = MikeMuted) }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onTravel, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
            ) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text("Travel", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TripPlannerPanel(
    state: MapsState,
    busy: Boolean,
    onEditOrigin: () -> Unit,
    onUseMyPosition: () -> Unit,
    onEditDest: () -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val origin = state.planOrigin
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trip", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = MikeMuted) }
            }
            PlannerRow(Icons.Filled.MyLocation, "From", origin?.name ?: "My position", MikeGreen, onEditOrigin)
            if (origin != null) {
                TextButton(onClick = onUseMyPosition, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Use my position", color = MikeAccent, fontSize = 13.sp)
                }
            }
            PlannerRow(Icons.Filled.Place, "To", state.planDest?.name ?: "—", MikeAccent, onEditDest)
            Spacer(Modifier.height(12.dp))
            if (busy && state.routeKm == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = MikeAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Finding the fastest route…", color = MikeMuted, fontSize = 13.sp)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(NavFormat.distance(state.routeKm ?: 0.0), "distance")
                    Metric(NavFormat.duration(state.routeEtaMin ?: 0.0), "drive")
                    if (origin == null) Metric(NavFormat.eta(state.routeEtaMin ?: 0.0), "arrival")
                }
            }
            Spacer(Modifier.height(14.dp))
            if (origin == null) {
                Button(
                    onClick = onStart, enabled = !busy && state.routeKm != null, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
                ) { Text("Start", fontWeight = FontWeight.Bold) }
            } else {
                Text("Preview from another location — that's how long it takes from there. Set From to “My position” to start navigating.",
                    color = MikeMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlannerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = MikeMuted, fontSize = 11.sp)
            Text(value, color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.Edit, "Edit", tint = MikeMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MenuSheet(
    state: MapsState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResume: (String) -> Unit,
    onChoose: (Suggestion) -> Unit,
    onToggleFavorite: (label: String, lat: Double?, lon: Double?) -> Unit,
    streetEnabled: Boolean = false,
    onStreetToggle: (Boolean) -> Unit = {},
    mapThemeMode: com.mikeos.maps.ui.MapTheme.Mode = com.mikeos.maps.ui.MapTheme.Mode.AUTO,
    onMapThemeChange: (com.mikeos.maps.ui.MapTheme.Mode) -> Unit = {},
    textSizeLevel: com.mikeos.maps.ui.TextSize.Level = com.mikeos.maps.ui.TextSize.Level.NORMAL,
    onTextSizeChange: (com.mikeos.maps.ui.TextSize.Level) -> Unit = {},
    nearLat: Double? = null,
    nearLon: Double? = null,
) {
    // The text field OWNS its text + cursor (TextFieldValue), full stop. It is seeded once from
    // state.query when the sheet opens (this composable only exists while menuOpen == true, so it
    // re-seeds on every open — that's how post-choose/post-end clears take effect).
    //
    // DO NOT add a LaunchedEffect(state.query){ field = … } feedback loop here. onQueryChange writes
    // each keystroke into the VM's StateFlow; StateFlow collection + recomposition lag behind fast
    // typing, so an effect keyed on state.query fires with a STALE value ("l'ange g") while the field
    // is already ahead ("l'ange gard") → it resets the field backwards, deleting the just-typed
    // letters AND nuking the IME's composing span (garbled keyboard-suggestion words). That bug made
    // the input unusable: "l'ange gardien" → "l'ange gein". The field is local; the VM never feeds
    // text back into it.
    var field by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    var settingsOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onQueryChange(it.text)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Where to?", color = MikeMuted) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                trailingIcon = {
                    if (field.text.isNotEmpty()) {
                        IconButton(onClick = {
                            field = TextFieldValue("")
                            onQueryChange("")
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MikeMuted)
                        }
                    }
                },
            )
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = onSearch,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), color = MikeBg, strokeWidth = 2.dp)
                else { Icon(Icons.Filled.Search, contentDescription = null); Text("Search", fontWeight = FontWeight.Bold) }
            }
        }

        if (state.query.isNotBlank()) {
            // RESULTS-FIRST — the instant you type, results are the hero. No settings, no history in
            // the way; the one thing you want (the places) sits right under the search box.
            when {
                state.suggestions.isNotEmpty() -> {
                    Spacer(Modifier.height(10.dp))
                    state.suggestions.forEach { s ->
                        val saved = state.favorites.any { it.label == s.label }
                        SuggestionRow(
                            s = s, saved = saved, nearLat = nearLat, nearLon = nearLon,
                            onClick = { onChoose(s) },
                            onToggleSave = { onToggleFavorite(s.label, s.lat, s.lon) },
                        )
                    }
                }
                state.busy -> {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = MikeAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Searching…", color = MikeMuted, fontSize = 14.sp)
                    }
                }
                else -> {
                    Spacer(Modifier.height(18.dp))
                    Text(state.notice ?: "No places found.", color = MikeMuted, fontSize = 14.sp)
                }
            }
        } else {
            // DEFAULT (nothing typed): recent destinations, then settings tucked behind a gear.
            Spacer(Modifier.height(18.dp))
            val trips = state.history.distinctBy { it.destName }   // collapse repeated destinations
            Text("RECENT", color = MikeMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            if (trips.isEmpty()) {
                Text("No trips yet. Search a place to go.", color = MikeMuted, fontSize = 13.sp)
            } else {
                trips.forEach { t -> TripRow(t, onClick = { t.destName?.let { onResume(it) } }) }
            }

            // Settings live OUT of the search flow — behind a gear, collapsed by default.
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { settingsOpen = !settingsOpen }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = MikeMuted)
                Spacer(Modifier.width(14.dp))
                Text("Settings", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(if (settingsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MikeMuted)
            }
            if (settingsOpen) {
                SettingsSection(
                    streetEnabled = streetEnabled, onStreetToggle = onStreetToggle,
                    mapThemeMode = mapThemeMode, onMapThemeChange = onMapThemeChange,
                    textSizeLevel = textSizeLevel, onTextSizeChange = onTextSizeChange,
                )
            }
        }
    }
}

/** The two settings that used to clutter the search flow — now behind the gear. */
@Composable
private fun SettingsSection(
    streetEnabled: Boolean,
    onStreetToggle: (Boolean) -> Unit,
    mapThemeMode: com.mikeos.maps.ui.MapTheme.Mode,
    onMapThemeChange: (com.mikeos.maps.ui.MapTheme.Mode) -> Unit,
    textSizeLevel: com.mikeos.maps.ui.TextSize.Level = com.mikeos.maps.ui.TextSize.Level.NORMAL,
    onTextSizeChange: (com.mikeos.maps.ui.TextSize.Level) -> Unit = {},
) {
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().clickable { onStreetToggle(!streetEnabled) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = MikeAccent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Give data to Mike Ecosystem", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Capture street imagery from the dashboard while you drive", color = MikeMuted, fontSize = 12.sp)
        }
        Switch(checked = streetEnabled, onCheckedChange = { onStreetToggle(it) })
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Brightness6, contentDescription = null, tint = MikeAccent)
        Spacer(Modifier.width(14.dp))
        Text("Map appearance", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.mikeos.maps.ui.MapTheme.Mode.entries.forEach { m ->
            val selected = m == mapThemeMode
            val label = when (m) {
                com.mikeos.maps.ui.MapTheme.Mode.AUTO -> "Auto"
                com.mikeos.maps.ui.MapTheme.Mode.LIGHT -> "Light"
                com.mikeos.maps.ui.MapTheme.Mode.DARK -> "Dark"
            }
            Surface(
                onClick = { onMapThemeChange(m) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MikeAccent else MikeSurface,
                contentColor = if (selected) MikeBg else MikeOnSurface,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    label, textAlign = TextAlign.Center, fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
    }

    // Text size — an accessibility control for the on-map business-name labels (readable at arm's length).
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.FormatSize, contentDescription = null, tint = MikeAccent)
        Spacer(Modifier.width(14.dp))
        Text("Map text size", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.mikeos.maps.ui.TextSize.Level.entries.forEach { lvl ->
            val selected = lvl == textSizeLevel
            Surface(
                onClick = { onTextSizeChange(lvl) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MikeAccent else MikeSurface,
                contentColor = if (selected) MikeBg else MikeOnSurface,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    lvl.label, textAlign = TextAlign.Center,
                    // Preview the size right on the chip so the effect is obvious.
                    fontSize = (13f * lvl.scale).sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The round European speed-limit sign (white disc, red ring, black number) with your current speed
 * under it. Shows only while driving and when we know the road's posted limit; the speed goes red when
 * you're over the limit.
 */
@Composable
private fun SpeedLimitSign(limit: Int, modifier: Modifier = Modifier) {
    // Just the posted-limit sign (white disc, red ring). The current speed lives in the HUD, once,
    // coloured by how far over the limit you are (see [speedColorFor]).
    Box(
        modifier.size(58.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(5.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limit", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** The current-speed colour: blue when legal/unknown, escalating red the more you exceed the limit. */
private fun speedColorFor(speed: Double, limit: Int?): androidx.compose.ui.graphics.Color {
    if (limit == null || speed <= limit + 2) return MikeAccent          // legal (or no limit) → blue
    val t = ((speed - limit - 2) / 18.0).toFloat().coerceIn(0f, 1f)     // 0 just-over … 1 by ~+20 km/h
    return androidx.compose.ui.graphics.lerp(Color(0xFFF2704B), Color(0xFFFF2A1F), t)  // red → redder
}

// ---- Small reusables -----------------------------------------------------------------------

@Composable
private fun SuggestionRow(
    s: Suggestion, saved: Boolean, nearLat: Double?, nearLon: Double?,
    onClick: () -> Unit, onToggleSave: () -> Unit,
) {
    // A place answers the human's questions in one glance: what it is (icon), which one it is (name),
    // and — the thing that decides everything — how far + which way it is. Region/country dropped.
    val name = s.label.substringBefore(",").trim().ifBlank { s.label }
    val where = s.label.split(",").getOrNull(1)?.trim().orEmpty()   // just the town — drop any region/country tail
    val distKm = if (nearLat != null && nearLon != null && s.lat != null && s.lon != null)
        NavGeo.haversineKm(nearLat, nearLon, s.lat, s.lon) else null
    val dir = if (distKm != null) NavGeo.compass(NavGeo.bearingDeg(nearLat!!, nearLon!!, s.lat!!, s.lon!!)) else null
    val sub = listOfNotNull(
        distKm?.let { fmtDistance(it) },
        dir,
        where.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MikeSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(if (s.fromHistory) "🕘" else categoryEmoji(s.category), fontSize = 18.sp) }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) {
                Text(sub, color = MikeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // ★ save/unsave — only for suggestions we have coordinates for.
        if (s.lat != null && s.lon != null) {
            IconButton(onClick = onToggleSave) {
                Icon(
                    if (saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (saved) "Remove from saved" else "Save place",
                    tint = if (saved) MikeAccent else MikeMuted,
                )
            }
        }
    }
}

/** Human distance: metres under 1 km, one decimal under 10 km, whole km beyond. */
private fun fmtDistance(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).roundToInt()} m"
    km < 10.0 -> "${"%.1f".format(km)} km"
    else -> "${km.roundToInt()} km"
}

/** A glanceable icon for an OSM category so you can tell a store from a street. */
private fun categoryEmoji(cat: String?): String = when (cat?.lowercase()) {
    "supermarket", "convenience", "grocery", "greengrocer", "department_store", "mall" -> "🛒"
    "fuel", "gas" -> "⛽"
    "charging_station" -> "🔌"
    "restaurant", "fast_food", "food_court" -> "🍽️"
    "cafe", "coffee" -> "☕"
    "bar", "pub", "biergarten" -> "🍺"
    "bakery" -> "🥖"
    "pharmacy", "chemist" -> "💊"
    "hospital", "clinic", "doctors" -> "🏥"
    "parking", "parking_space", "parking_entrance" -> "🅿️"
    "hotel", "guest_house", "hostel", "motel" -> "🏨"
    "bank", "atm", "bureau_de_change" -> "🏧"
    "school", "university", "college", "kindergarten" -> "🎓"
    "bus_stop", "bus_station", "station", "stop", "halt", "tram_stop" -> "🚉"
    "aerodrome", "airport", "terminal" -> "✈️"
    "beach", "beach_resort" -> "🏖️"
    "park", "garden", "playground" -> "🌳"
    "hairdresser", "beauty" -> "💈"
    "post_office" -> "📮"
    "police" -> "🚓"
    "place_of_worship", "church" -> "⛪"
    "city", "town", "village", "hamlet", "suburb", "neighbourhood", "locality" -> "🏘️"
    else -> "📍"
}

/**
 * Dedicated saved-places screen (behind the ★ top-right). Shows ONLY starred places, latest-used
 * first, searchable, in a LazyColumn so it scales to thousands without janking.
 */
@Composable
private fun SavedPlacesSheet(
    favorites: List<com.mikeos.maps.data.SavedPlace>,
    onChoose: (com.mikeos.maps.data.SavedPlace) -> Unit,
    onRemove: (com.mikeos.maps.data.SavedPlace) -> Unit,
) {
    // Local query state → no async feedback, so no cursor-jump (see the MenuSheet note).
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, favorites) {
        val q = query.trim()
        if (q.isBlank()) favorites
        else favorites.filter { it.label.contains(q, ignoreCase = true) || it.shortName.contains(q, ignoreCase = true) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        Text("SAVED PLACES · ${favorites.size}", color = MikeAccent, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search saved places", color = MikeMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MikeMuted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MikeMuted)
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                if (favorites.isEmpty()) "No saved places yet — tap ★ on a search result to save one."
                else "No saved place matches \"$query\".",
                color = MikeMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.label }) { p ->
                    FavoriteRow(p, onClick = { onChoose(p) }, onRemove = { onRemove(p) })
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(p: com.mikeos.maps.data.SavedPlace, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MikeAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            p.label, color = MikeOnSurface, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Star, contentDescription = "Remove from saved", tint = MikeAccent)
        }
    }
}

@Composable
private fun CompassButton(
    appliedBearing: Double,
    northUp: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MikeSurface.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = if (northUp) "North up — tap to follow heading" else "Heading up — tap for north up",
            tint = MikeRed,
            modifier = Modifier.size(22.dp).rotate(-appliedBearing.toFloat()),
        )
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MikeSurface.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Metric(value: String, label: String, hero: Boolean = false, heroColor: androidx.compose.ui.graphics.Color = MikeAccent) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = if (hero) heroColor else MikeOnSurface,
            fontSize = if (hero) 26.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(label, color = MikeMuted, fontSize = 10.sp)
    }
}

@Composable
private fun TripRow(t: TripsCloudClient.Trip, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MikeSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("🚗", fontSize = 16.sp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.destName ?: "(trip)", color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            // Just the trip length — avg km/h and sample counts are trivia when re-picking a destination.
            t.km?.let { Text("${"%.1f".format(it)} km", color = MikeMuted, fontSize = 12.sp, maxLines = 1) }
        }
        t.durationMin?.let {
            Text("${"%.0f".format(it)} min", color = MikeMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        // Tap affordance — drive this destination again.
        Spacer(Modifier.size(10.dp))
        Icon(Icons.Filled.PlayArrow, contentDescription = "Drive again", tint = MikeAccent, modifier = Modifier.size(20.dp))
    }
}
