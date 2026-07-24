package com.mikeos.maps

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.maps.agent.MapsMikeAgent
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

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        // Embed the shared MikeAgent runtime (soul + nav skills + heartbeat + live hive).
        MapsMikeAgent.install(this)

        setContent {
            MikeOsTheme {
                val vm: MapsViewModel = viewModel()
                MapFirstScreen(vm)
            }
        }
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
    val context = LocalContext.current

    var follow by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    // Poll the daemon fix while the map is on screen (moving dot + prefetch + HUD).
    DisposableEffect(Unit) {
        vm.startLiveLocation()
        onDispose { vm.stopLiveLocation() }
    }

    Box(Modifier.fillMaxSize().background(MikeBg)) {

        // THE MAP — the whole screen. Map-first.
        NavigationMap(
            location = location,
            routePoints = state.routePoints,
            follow = follow,
            onUserPan = { follow = false },
            modifier = Modifier.fillMaxSize(),
        )

        // Top overlay: ☰ menu (search + options) and the mandatory agent window.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MikeOnSurface)
            }
            Spacer(Modifier.weight(1f))
            com.mikeos.core.ui.AgentIconButton(
                onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
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

            state.notice?.let {
                NoticePill(it)
                Spacer(Modifier.height(10.dp))
            }

            val a = active
            if (a != null) {
                DrivingHud(a, navInfo, busy = state.busy, onEnd = { vm.endTrip() })
            } else {
                WhereToBar(location, onClick = { menuOpen = true })
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
                onGo = {
                    vm.go()
                    follow = true
                    menuOpen = false
                },
            )
        }
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NAVIGATING", color = MikeGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("→ ${a.destName}", color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                Button(
                    onClick = onEnd,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeRed, contentColor = MikeOnSurface),
                ) { Text("End", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(14.dp))
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
    onGo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text("MIKEMAPS", color = MikeAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("navigation + road sensing", color = MikeMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Where to?", color = MikeMuted) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
            )
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = onGo,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), color = MikeBg, strokeWidth = 2.dp)
                else { Icon(Icons.Filled.PlayArrow, contentDescription = null); Text("Go", fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("TRIP HISTORY · ${state.history.size}", color = MikeMuted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        if (state.history.isEmpty()) {
            Text("No trips yet. Route somewhere to start recording.", color = MikeMuted, fontSize = 13.sp)
        } else {
            state.history.forEach { t -> TripRow(t) }
        }
    }
}

// ---- Small reusables -----------------------------------------------------------------------

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
private fun TripRow(t: TripsCloudClient.Trip) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 2.dp),
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
    }
}
