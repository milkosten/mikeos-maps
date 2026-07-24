package com.mikeos.maps

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.maps.agent.MapsMikeAgent
import com.mikeos.maps.net.PolylineCodec
import com.mikeos.maps.net.TripsCloudClient
import com.mikeos.maps.trips.TripManager
import com.mikeos.maps.ui.theme.MikeAccent
import com.mikeos.maps.ui.theme.MikeBg
import com.mikeos.maps.ui.theme.MikeGreen
import com.mikeos.maps.ui.theme.MikeMuted
import com.mikeos.maps.ui.theme.MikeOnSurface
import com.mikeos.maps.ui.theme.MikeOsTheme
import com.mikeos.maps.ui.theme.MikeRed
import com.mikeos.maps.ui.theme.MikeSurface
import com.mikeos.maps.ui.theme.MikeSurfaceVariant

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
                val state by vm.state.collectAsStateWithLifecycle()
                val active by vm.active.collectAsStateWithLifecycle()
                MapsScreen(
                    state = state,
                    active = active,
                    onQueryChange = vm::onQueryChange,
                    onGo = vm::go,
                    onEnd = vm::endTrip,
                )
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

@Composable
private fun MapsScreen(
    state: MapsState,
    active: TripManager.ActiveTrip?,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
    onEnd: () -> Unit,
) {
    Scaffold(containerColor = MikeBg) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                val context = LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MIKEMAPS",
                            color = MikeAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Text("navigation + road sensing", color = MikeMuted, fontSize = 12.sp)
                    }
                    // MANDATORY: one-tap window into this app's living agent.
                    com.mikeos.core.ui.AgentIconButton(
                        onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
                    )
                }
                Spacer(Modifier.height(14.dp))

                // Destination input + Go.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Where to?", color = MikeMuted) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        enabled = active == null,
                    )
                    Spacer(Modifier.size(10.dp))
                    Button(
                        onClick = onGo,
                        enabled = !state.busy && active == null,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MikeAccent, contentColor = MikeBg),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = MikeBg, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("Go", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                state.notice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MikeMuted, fontSize = 12.sp)
                }

                // The route render (decoded polyline traced on a neutral canvas — no map tiles).
                if (state.routePoints.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    RouteCanvas(state.routePoints)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        state.routeKm?.let { StatChip("${"%.1f".format(it)} km") }
                        Spacer(Modifier.size(8.dp))
                        state.routeEtaMin?.let { StatChip("ETA ${"%.0f".format(it)} min") }
                    }
                    state.destName?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MikeMuted, fontSize = 12.sp, maxLines = 2)
                    }
                }

                // Active-trip card.
                if (active != null) {
                    Spacer(Modifier.height(16.dp))
                    ActiveTripCard(active, onEnd, busy = state.busy)
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "TRIP HISTORY · ${state.history.size}",
                    color = MikeMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (state.history.isEmpty()) {
                    Text("No trips yet. Route somewhere to start recording.", color = MikeMuted, fontSize = 13.sp)
                }
            }

            items(state.history, key = { it.tripId }) { t -> TripRow(t) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RouteCanvas(points: List<PolylineCodec.LatLon>) {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val latSpan = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
    val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 } ?: 1e-9

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .background(MikeSurface),
    ) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            val w = size.width
            val h = size.height
            fun px(p: PolylineCodec.LatLon): Offset {
                val x = ((p.lon - minLon) / lonSpan) * w
                // latitude increases upward → invert y
                val y = (1.0 - (p.lat - minLat) / latSpan) * h
                return Offset(x.toFloat(), y.toFloat())
            }
            val path = Path().apply {
                val first = px(points.first())
                moveTo(first.x, first.y)
                for (i in 1 until points.size) {
                    val o = px(points[i])
                    lineTo(o.x, o.y)
                }
            }
            drawPath(path, color = MikeAccent, style = Stroke(width = 6f, cap = StrokeCap.Round))
            // start (green) + end (red) dots
            drawCircle(MikeGreen, radius = 12f, center = px(points.first()))
            drawCircle(MikeRed, radius = 12f, center = px(points.last()))
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MikeSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = MikeOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActiveTripCard(a: TripManager.ActiveTrip, onEnd: () -> Unit, busy: Boolean) {
    val elapsedMin = ((System.currentTimeMillis() - a.startedAtMs) / 60000.0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MikeSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("ACTIVE TRIP", color = MikeGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text("→ ${a.destName}", color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(a.lastSpeedKmh?.let { "${"%.0f".format(it)}" } ?: "—", "km/h")
                Metric("${"%.0f".format(elapsedMin)}", "min")
                Metric("${"%.1f".format(a.distanceSoFarKm)}", "km driven")
                Metric("${a.samplesPosted}", "samples")
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onEnd,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MikeRed, contentColor = MikeOnSurface),
            ) {
                Text("End trip", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MikeAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = MikeMuted, fontSize = 10.sp)
    }
}

@Composable
private fun TripRow(t: TripsCloudClient.Trip) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MikeSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚗", fontSize = 16.sp)
        }
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
