package com.mikeos.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.maps.nav.Guidance
import com.mikeos.maps.nav.NavGeo
import com.mikeos.maps.nav.NavGuidance
import com.mikeos.maps.nav.NavInfo
import com.mikeos.maps.nav.Speaker
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.Geocoder
import com.mikeos.maps.net.OfflinePrefetch
import com.mikeos.maps.net.PolylineCodec
import com.mikeos.maps.net.TripsCloudClient
import com.mikeos.maps.trips.TripManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The routing / active-trip / history screen state. */
data class MapsState(
    val query: String = "",
    val busy: Boolean = false,
    val notice: String? = null,
    // The most recently computed (or active) route, decoded for the map.
    val routePoints: List<PolylineCodec.LatLon> = emptyList(),
    val routeKm: Double? = null,
    val routeEtaMin: Double? = null,
    val destName: String? = null,
    // Turn-by-turn maneuvers for the current route (empty until steps arrive).
    val routeSteps: List<TripsCloudClient.RouteStep> = emptyList(),
    // A route has been computed + framed but the trip hasn't started yet (preview → Start).
    val previewing: Boolean = false,
    val history: List<TripsCloudClient.Trip> = emptyList(),
)

class MapsViewModel(app: Application) : AndroidViewModel(app) {

    private val trips = TripManager.get(app)

    private val _state = MutableStateFlow(MapsState())
    val state: StateFlow<MapsState> = _state.asStateFlow()

    /** The active trip, straight from the manager (drives the live HUD). */
    val active: StateFlow<TripManager.ActiveTrip?> = trips.active

    /** The live device location (the ONE daemon fix), polled while the map is on screen. */
    private val _location = MutableStateFlow<DaemonLocation.Fix?>(null)
    val location: StateFlow<DaemonLocation.Fix?> = _location.asStateFlow()

    /** The live driving HUD readout (speed / remaining / ETA); null when not navigating. */
    private val _navInfo = MutableStateFlow<NavInfo?>(null)
    val navInfo: StateFlow<NavInfo?> = _navInfo.asStateFlow()

    /** The live turn-by-turn guidance (next maneuver); null when not navigating. */
    private val _guidance = MutableStateFlow<Guidance?>(null)
    val guidance: StateFlow<Guidance?> = _guidance.asStateFlow()

    private var locationJob: Job? = null

    // A previewed-but-not-started route (see [preview] → [startPreviewed] / [cancelPreview]).
    private var pendingPlace: Geocoder.Place? = null
    private var pendingFix: DaemonLocation.Fix? = null
    private var pendingRoute: TripsCloudClient.Route? = null

    // Turn-by-turn tracking, reset whenever the route changes.
    private var maneuverIdx = 0
    private var offRouteTicks = 0
    private var arrived = false
    @Volatile private var rerouting = false

    // ETA smoothing — a moving average of speed (+ EMA of the ETA) so it stays stable instead of
    // swinging from 20 to 50 min every time Mike speeds up or crawls.
    private var emaSpeedKmh = 0.0
    private var emaSpeedSeeded = false
    private var emaEtaMin = Double.NaN

    init {
        loadHistory()
    }

    // ---- LIVE LOCATION (map-first: the moving dot + prefetch + HUD) ------------------------

    /** Begin polling the daemon fix (~every [LOCATION_POLL_MS]) while the map is visible. */
    fun startLiveLocation() {
        if (locationJob?.isActive == true) return
        Speaker.init(getApplication())
        locationJob = viewModelScope.launch {
            while (isActive) {
                val fix = trips.currentFix()
                if (fix != null) {
                    _location.value = fix
                    // Keep ~100 km around Mike cached so the map is instant / offline-resilient.
                    OfflinePrefetch.ensureAround(getApplication(), fix.lat, fix.lon)
                    recomputeNav(fix)
                }
                delay(LOCATION_POLL_MS)
            }
        }
    }

    fun stopLiveLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    private fun recomputeNav(fix: DaemonLocation.Fix) {
        val a = active.value
        val pts = _state.value.routePoints
        if (a == null || pts.size < 2) {
            _navInfo.value = null
            _guidance.value = null
            return
        }
        // Smoothed ETA: a moving average of speed drives a stable ETA (instantaneous speed made it
        // jump wildly). The live estimate is also logged with the 5s samples for later analysis.
        val remKm = NavGeo.remainingKm(pts, fix.lat, fix.lon)
        val plannedAvg = if (a.etaMin > 0.5 && a.km > 0) a.km / (a.etaMin / 60.0) else 40.0
        val rawKmh = fix.speedKmh ?: 0.0
        emaSpeedKmh = if (!emaSpeedSeeded) {
            emaSpeedSeeded = true
            if (rawKmh > 1.0) rawKmh else plannedAvg
        } else {
            emaSpeedKmh * (1 - SPEED_EMA_ALPHA) + rawKmh * SPEED_EMA_ALPHA
        }
        val effSpeed = emaSpeedKmh.coerceAtLeast(4.0)   // floor so a brief stop doesn't explode the ETA
        val rawEta = remKm / effSpeed * 60.0
        emaEtaMin = if (emaEtaMin.isNaN()) rawEta else emaEtaMin * (1 - ETA_EMA_ALPHA) + rawEta * ETA_EMA_ALPHA
        // Speedometer shows the ACTUAL current speed; ETA/remaining use the smoothed value.
        _navInfo.value = NavInfo(speedKmh = rawKmh, remainingKm = remKm, remainingMin = emaEtaMin)
        trips.lastEtaMin = emaEtaMin
        trips.lastRemainingKm = remKm

        // Turn-by-turn: advance to the next maneuver ahead.
        val steps = _state.value.routeSteps
        if (steps.isNotEmpty()) {
            NavGuidance.next(steps, maneuverIdx, fix.lat, fix.lon)?.let { (g, idx) ->
                _guidance.value = g
                maneuverIdx = idx
                Speaker.announce(g)
            }
        }

        // Arrival: auto-end once within ARRIVE_M of the destination.
        val toDestM = NavGeo.haversineKm(fix.lat, fix.lon, a.destLat, a.destLon) * 1000.0
        if (!arrived && toDestM < ARRIVE_M) {
            arrived = true
            _state.value = _state.value.copy(notice = "Arrived at ${a.destName}.")
            endTrip()
            return
        }

        // Off-route: use CROSS-TRACK distance to the route line (not nearest vertex — that falsely
        // fires on long straight segments). Require several consecutive ticks so GPS wobble on-road
        // never triggers a reroute.
        val offM = NavGeo.distanceToRouteM(pts, fix.lat, fix.lon)
        if (offM > OFF_ROUTE_M) {
            offRouteTicks++
            if (offRouteTicks >= OFF_ROUTE_TICKS) reroute(fix.lat, fix.lon, a)
        } else {
            offRouteTicks = 0
        }
    }

    /** Recompute the route from the current position to the destination (same trip keeps recording). */
    private fun reroute(curLat: Double, curLon: Double, a: TripManager.ActiveTrip) {
        if (rerouting) return
        rerouting = true
        viewModelScope.launch {
            val r = trips.route(curLat, curLon, a.destLat, a.destLon)
            if (r != null) {
                val pts = runCatching { PolylineCodec.decode(r.polyline) }.getOrDefault(emptyList())
                maneuverIdx = 0
                offRouteTicks = 0
                _state.value = _state.value.copy(
                    routePoints = pts,
                    routeSteps = r.steps,
                    routeKm = r.km,
                    routeEtaMin = r.etaMin,
                    notice = "Rerouting…",
                )
            }
            rerouting = false
        }
    }

    private fun resetGuidanceTrackers() {
        maneuverIdx = 0
        offRouteTicks = 0
        arrived = false
        emaSpeedKmh = 0.0
        emaSpeedSeeded = false
        emaEtaMin = Double.NaN
        trips.lastEtaMin = null
        trips.lastRemainingKm = null
        Speaker.reset()
    }

    override fun onCleared() {
        super.onCleared()
        Speaker.shutdown()
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    /**
     * PREVIEW: geocode the destination → read the daemon fix → route → FRAME it on the map. Stores
     * the pending route but does NOT start the trip — the user confirms with [startPreviewed].
     * (Google-Maps flow: search → see the route → Start.)
     */
    fun preview() {
        val dest = _state.value.query.trim()
        if (dest.isBlank()) {
            _state.value = _state.value.copy(notice = "Type a destination first.")
            return
        }
        if (active.value != null) {
            _state.value = _state.value.copy(notice = "A trip is already active. End it first.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Finding $dest…")
            val place = trips.geocode(dest)
            if (place == null) {
                _state.value = _state.value.copy(busy = false, notice = "Couldn't find \"$dest\".")
                return@launch
            }
            _state.value = _state.value.copy(notice = "Reading your location…")
            val fix = trips.currentFix()
            if (fix == null) {
                _state.value = _state.value.copy(busy = false, notice = "No location fix from the daemon (GPS provider may be down).")
                return@launch
            }
            _state.value = _state.value.copy(notice = "Routing…")
            val route = trips.route(fix.lat, fix.lon, place.lat, place.lon)
            if (route == null) {
                _state.value = _state.value.copy(busy = false, notice = "Couldn't compute a route to \"$dest\".")
                return@launch
            }
            val points = runCatching { PolylineCodec.decode(route.polyline) }.getOrDefault(emptyList())
            pendingPlace = place
            pendingFix = fix
            pendingRoute = route
            resetGuidanceTrackers()
            _state.value = _state.value.copy(
                busy = false,
                notice = null,
                routePoints = points,
                routeSteps = route.steps,
                routeKm = route.km,
                routeEtaMin = route.etaMin,
                destName = place.name,
                previewing = true,
            )
        }
    }

    /** Resume a past drive (tapped in history): preview a route to that named destination. */
    fun previewDestination(dest: String) {
        _state.value = _state.value.copy(query = dest)
        preview()
    }

    /** START the previewed trip (create in cloud + broadcast trip.started + start the 5s sampler). */
    fun startPreviewed() {
        val place = pendingPlace
        val fix = pendingFix
        val route = pendingRoute
        if (place == null || fix == null || route == null) {
            _state.value = _state.value.copy(notice = "Nothing to start — preview a destination first.")
            return
        }
        resetGuidanceTrackers()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Starting trip…", previewing = false)
            val id = trips.startTrip(place.name, place.lat, place.lon, fix.lat, fix.lon, route)
            pendingPlace = null; pendingFix = null; pendingRoute = null
            _state.value = _state.value.copy(
                busy = false,
                notice = if (id != null) "Navigating — recording the drive." else "Trip created but the cloud didn't confirm it.",
            )
        }
    }

    /** Discard the previewed route (back to the map). */
    fun cancelPreview() {
        pendingPlace = null; pendingFix = null; pendingRoute = null
        _guidance.value = null
        _state.value = _state.value.copy(
            previewing = false,
            routePoints = emptyList(),
            routeSteps = emptyList(),
            routeKm = null,
            routeEtaMin = null,
            destName = null,
            notice = null,
        )
    }

    /** End the active trip (POST end + broadcast trip.ended). */
    fun endTrip() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Ending trip…")
            val s = trips.endTrip()
            _state.value = _state.value.copy(
                busy = false,
                notice = if (s != null) "Trip ended: ${"%.0f".format(s.durationMin)} min, ${s.sampleCount} samples." else "Ended locally (cloud unconfirmed).",
                // Clear the route from the map once the trip is done.
                routePoints = emptyList(),
                routeSteps = emptyList(),
                routeKm = null,
                routeEtaMin = null,
                destName = null,
            )
            _navInfo.value = null
            _guidance.value = null
            loadHistory()
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            val list = trips.recentTrips()
            _state.value = _state.value.copy(history = list)
        }
    }

    companion object {
        private const val LOCATION_POLL_MS = 3_000L
        private const val ARRIVE_M = 40.0      // auto-end the trip within this of the destination
        private const val OFF_ROUTE_M = 70.0   // reroute when the cross-track distance exceeds this
        private const val OFF_ROUTE_TICKS = 4  // …for this many consecutive polls (~12s) — avoids noise
        private const val SPEED_EMA_ALPHA = 0.08  // long-window speed average → stable ETA
        private const val ETA_EMA_ALPHA = 0.15    // extra smoothing on the ETA itself
    }
}
