package com.mikeos.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.maps.nav.NavInfo
import com.mikeos.maps.net.DaemonLocation
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

    private var locationJob: Job? = null

    init {
        loadHistory()
    }

    // ---- LIVE LOCATION (map-first: the moving dot + prefetch + HUD) ------------------------

    /** Begin polling the daemon fix (~every [LOCATION_POLL_MS]) while the map is visible. */
    fun startLiveLocation() {
        if (locationJob?.isActive == true) return
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
        _navInfo.value =
            if (a != null && pts.size >= 2)
                NavInfo.compute(fix.speedKmh, pts, fix.lat, fix.lon, a.km, a.etaMin)
            else null
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    /**
     * "Go": geocode the destination → read the daemon fix → route → show it → START the trip
     * (create + broadcast trip.started). All deterministic, wired directly (not via the LLM).
     */
    fun go() {
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
            _state.value = _state.value.copy(
                routePoints = points,
                routeKm = route.km,
                routeEtaMin = route.etaMin,
                destName = place.name,
                notice = "Starting trip…",
            )
            // START the trip (create in cloud + broadcast trip.started + start the 5s sampler).
            val id = trips.startTrip(place.name, place.lat, place.lon, fix.lat, fix.lon, route)
            _state.value = _state.value.copy(
                busy = false,
                notice = if (id != null) "Trip started — recording the drive." else "Trip created but the cloud didn't confirm it.",
            )
        }
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
                routeKm = null,
                routeEtaMin = null,
                destName = null,
            )
            _navInfo.value = null
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
    }
}
