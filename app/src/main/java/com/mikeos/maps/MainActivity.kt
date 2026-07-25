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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import com.mikeos.maps.net.TripsCloudClient
import com.mikeos.maps.trips.TripManager
import com.mikeos.maps.ui.NavigationMap
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
        pendingDest.value = parseDest(intent)

        setContent {
            MikeOsTheme {
                val vm: MapsViewModel = viewModel()
                MapFirstScreen(vm)
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

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapFirstScreen(vm: MapsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val navInfo by vm.navInfo.collectAsStateWithLifecycle()
    val guidance by vm.guidance.collectAsStateWithLifecycle()
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

    // Follow the dot everywhere EXCEPT while previewing a route (then we frame the whole route).
    LaunchedEffect(state.previewing, active != null) {
        follow = !state.previewing
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
            modifier = Modifier.fillMaxSize(),
        )

        // Compass — tap to toggle heading-up (default while driving) ↔ true-north. The red needle
        // points to real north (rotates opposite the map's bearing).
        CompassButton(
            appliedBearing = if (active != null && !northUp) (location?.bearing ?: 0.0) else 0.0,
            northUp = northUp,
            onClick = { northUp = !northUp },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
        )

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
            when {
                a != null -> DrivingHud(a, navInfo, busy = state.busy, onEnd = { vm.endTrip() })
                state.previewing -> RoutePreviewPanel(
                    state = state,
                    busy = state.busy,
                    onStart = { vm.startPreviewed() },
                    onCancel = { vm.cancelPreview() },
                )
                state.tappedPlace != null -> TappedPlaceCard(
                    place = state.tappedPlace!!,
                    busy = state.busy,
                    onDirections = { vm.directionsToTappedPlace() },
                    onDismiss = { vm.dismissTappedPlace() },
                )
                else -> WhereToBar(location, onClick = { menuOpen = true })
            }
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
    onEnd: () -> Unit,
) {
    val speed = navInfo?.speedKmh ?: a.lastSpeedKmh ?: 0.0
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
                Metric("${speed.roundToInt()}", "km/h", hero = true)
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

/** Google-Maps-style card for a POI tapped on the map: its name + a one-tap Directions button. */
@Composable
private fun TappedPlaceCard(
    place: TappedPoi,
    busy: Boolean,
    onDirections: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                    Text("PLACE", color = MikeAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(place.name, color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MikeMuted)
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
private fun MenuSheet(
    state: MapsState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResume: (String) -> Unit,
    onChoose: (Suggestion) -> Unit,
    onToggleFavorite: (label: String, lat: Double?, lon: Double?) -> Unit,
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

        // Type-ahead suggestions (history first, then places) — tap to preview, tap ★ to save.
        if (state.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            state.suggestions.forEach { s ->
                val saved = state.favorites.any { it.label == s.label }
                SuggestionRow(
                    s = s,
                    saved = saved,
                    onClick = { onChoose(s) },
                    onToggleSave = { onToggleFavorite(s.label, s.lat, s.lon) },
                )
            }
        }

        // (Saved places live behind the ★ button top-right — a dedicated, searchable, scalable screen.)

        Spacer(Modifier.height(22.dp))
        Text("TRIP HISTORY · ${state.history.size}", color = MikeMuted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        if (state.history.isEmpty()) {
            Text("No trips yet. Route somewhere to start recording.", color = MikeMuted, fontSize = 13.sp)
        } else {
            Text("Tap a trip to drive it again.", color = MikeMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            state.history.forEach { t ->
                TripRow(t, onClick = { t.destName?.let { onResume(it) } })
            }
        }
    }
}

// ---- Small reusables -----------------------------------------------------------------------

@Composable
private fun SuggestionRow(s: Suggestion, saved: Boolean, onClick: () -> Unit, onToggleSave: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (s.fromHistory) Icons.Filled.History else Icons.Filled.Place,
            contentDescription = null,
            tint = if (s.fromHistory) MikeAccent else MikeMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            s.label, color = MikeOnSurface, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
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
private fun Metric(value: String, label: String, hero: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = if (hero) MikeAccent else MikeOnSurface,
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
            val bits = buildList {
                t.km?.let { add("${"%.1f".format(it)} km") }
                t.avgKmh?.let { add("avg ${"%.0f".format(it)} km/h") }
                t.sampleCount?.let { add("$it samples") }
            }
            if (bits.isNotEmpty()) Text(bits.joinToString(" · "), color = MikeMuted, fontSize = 12.sp, maxLines = 1)
        }
        t.durationMin?.let {
            Text("${"%.0f".format(it)} min", color = MikeMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        // Tap affordance — drive this destination again.
        Spacer(Modifier.size(10.dp))
        Icon(Icons.Filled.PlayArrow, contentDescription = "Drive again", tint = MikeAccent, modifier = Modifier.size(20.dp))
    }
}
