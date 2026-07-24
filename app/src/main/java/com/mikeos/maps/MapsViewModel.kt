package com.mikeos.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.maps.net.PolylineCodec
import com.mikeos.maps.net.TripsCloudClient
import com.mikeos.maps.trips.TripManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The routing / active-trip / history screen state. */
data class MapsState(
    val query: String = "",
    val busy: Boolean = false,
    val notice: String? = null,
    // The most recently computed (or active) route, decoded for the canvas.
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

    /** The active trip, straight from the manager (drives the live card). */
    val active: StateFlow<TripManager.ActiveTrip?> = trips.active

    init {
        loadHistory()
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
            // START the trip (create in cloud + broadcast trip.started).
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
            )
            loadHistory()
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            val list = trips.recentTrips()
            _state.value = _state.value.copy(history = list)
        }
    }
}
