package com.mikeos.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.maps.nav.Guidance
import com.mikeos.maps.nav.NavGeo
import com.mikeos.maps.nav.NavGuidance
import com.mikeos.maps.data.PlacesRepo
import com.mikeos.maps.data.SavedPlace
import com.mikeos.maps.nav.NavInfo
import com.mikeos.maps.nav.Speaker
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.Geocoder
import com.mikeos.maps.net.NearbySearch
import com.mikeos.maps.net.OfflinePrefetch
import com.mikeos.maps.net.PoiSearch
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

/** A type-ahead destination suggestion — from trip history (coords null → re-geocoded) or Nominatim. */
data class Suggestion(
    val label: String,
    val lat: Double?,
    val lon: Double?,
    val fromHistory: Boolean,
)

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
    // Live type-ahead suggestions while entering a destination.
    val suggestions: List<Suggestion> = emptyList(),
    val history: List<TripsCloudClient.Trip> = emptyList(),
    // Mike's ⭐-saved places (homes / work / favorites) — the quick-pick list in the search sheet.
    val favorites: List<SavedPlace> = emptyList(),
    // A POI tapped directly on the map (Super U, a bus stop, a place label) — awaiting a Directions tap.
    val tappedPlace: TappedPoi? = null,
    // "Explore nearby" (🔍): parking/fuel/EV around the destination (or the user). Sheet + results.
    val nearbyOpen: Boolean = false,
    val nearbyBusy: Boolean = false,
    val nearbyAnchor: String? = null,          // what we searched around ("Café de Paris" / "you")
    val nearbyMode: String = "dest",           // "dest" (at destination) | "route" (along the road) | "you"
    val nearbyHasRoute: Boolean = false,       // a route exists → offer the At-destination/Along-route toggle
    val nearby: List<NearbySearch.Place> = emptyList(),
)

/** A feature the user tapped on the map surface, offered for one-tap directions. */
data class TappedPoi(val name: String, val lat: Double, val lon: Double)

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
    private var suggestJob: Job? = null

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
        loadFavorites()
    }

    // ---- SAVED PLACES (⭐) ------------------------------------------------------------------

    fun loadFavorites() {
        viewModelScope.launch {
            _state.value = _state.value.copy(favorites = PlacesRepo.favorites(getApplication()))
        }
    }

    /**
     * Toggle a place as a ⭐ favorite. On SAVE it announces `place.saved` on the hive (via MikeAgent)
     * so Guide/Storyteller/Mind react. Needs coordinates (a coordless history hit can't be saved yet).
     */
    fun toggleFavorite(label: String, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) {
            _state.value = _state.value.copy(notice = "Can't save that one — no location for it yet.")
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val nowFav = !PlacesRepo.isFavorite(app, label)
            val row = PlacesRepo.setFavorite(app, label, lat, lon, favorite = nowFav, kind = "favorite")
            loadFavorites()
            if (nowFav) {
                runCatching {
                    trips.announcePlaceSaved(row.label, row.shortName, row.lat, row.lon, row.kind ?: "favorite")
                }
            }
        }
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
        // --- ETA -------------------------------------------------------------------------------
        // The live estimate is logged with the 5s samples for later analysis.
        val remKm = NavGeo.remainingKm(pts, fix.lat, fix.lon)
        val rawKmh = fix.speedKmh ?: 0.0
        val plannedAvg = if (a.etaMin > 0.5 && a.km > 0) a.km / (a.etaMin / 60.0) else 40.0
        emaSpeedKmh = if (!emaSpeedSeeded) {
            emaSpeedSeeded = true
            if (rawKmh > 1.0) rawKmh else plannedAvg
        } else {
            emaSpeedKmh * (1 - SPEED_EMA_ALPHA) + rawKmh * SPEED_EMA_ALPHA
        }

        // Preferred: OSRM's planned time for the road AHEAD (highway-aware), scaled by our cumulative
        // actual-vs-planned pace — trusting the plan early and the measured pace more as we progress.
        // Fallback (no step durations): remaining distance ÷ moving-average speed.
        val plannedRemMin = NavGuidance.plannedRemainingMin(_state.value.routeSteps, remKm)
        val rawEta: Double = if (plannedRemMin != null && a.km > 0 && a.etaMin > 0.5) {
            val elapsedMin = (System.currentTimeMillis() - a.startedAtMs) / 60_000.0
            val plannedForCovered = (a.etaMin - plannedRemMin).coerceAtLeast(0.0)
            val trust = ((a.km - remKm) / a.km).coerceIn(0.0, 1.0)
            val rawRatio = if (plannedForCovered > 0.3) elapsedMin / plannedForCovered else 1.0
            val ratio = ((1.0 - trust) + trust * rawRatio).coerceIn(0.6, 2.5)
            plannedRemMin * ratio
        } else {
            remKm / emaSpeedKmh.coerceAtLeast(4.0) * 60.0
        }
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
        suggestJob?.cancel()
        val query = q.trim()
        if (query.length < 3) {
            _state.value = _state.value.copy(suggestions = emptyList())
            return
        }
        // Debounced type-ahead — offline cache + history show instantly, online candidates follow.
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            _state.value = _state.value.copy(suggestions = suggestFor(query))
        }
    }

    /** Explicit SEARCH (the button): produce a choosable list of results, and LOG the search. */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isBlank()) {
            _state.value = _state.value.copy(notice = "Type a destination to search.")
            return
        }
        if (active.value != null) {
            _state.value = _state.value.copy(notice = "A trip is already active. End it first.")
            return
        }
        suggestJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            val results = suggestFor(q, includePoi = true)
            _state.value = _state.value.copy(
                busy = false,
                suggestions = results,
                notice = if (results.isEmpty()) "No places found for \"$q\"." else null,
            )
            val near = _location.value
            runCatching { trips.logSearch(q, results.size, nearLat = near?.lat, nearLon = near?.lon) }
        }
    }

    /**
     * Build suggestions: 1) offline fuzzy cache, 2) cloud-history names, 3) named POIs (Overpass —
     * only when [includePoi], i.e. the explicit Search, since it's heavier), 4) location-biased
     * geocoder. Deduped by the SHORT name so 3× "Avenue de l'Ange Gardien" collapses to one.
     */
    private suspend fun suggestFor(query: String, includePoi: Boolean = false): List<Suggestion> {
        val near = _location.value
        val local = runCatching { PlacesRepo.search(getApplication(), query, 5) }
            .getOrDefault(emptyList())
            .map { Suggestion(it.label, it.lat, it.lon, fromHistory = true) }
        val hist = _state.value.history
            .mapNotNull { it.destName }
            .filter { it.contains(query, ignoreCase = true) }
            .distinct()
            .take(2)
            .map { Suggestion(it, null, null, fromHistory = true) }
        val poi = if (includePoi && near != null) {
            // Fetch the full nearby set (proximity query) so the closest-first sort below is correct.
            runCatching { PoiSearch.search(query, near.lat, near.lon, 40) }.getOrDefault(emptyList())
                .map { Suggestion(it.name, it.lat, it.lon, fromHistory = false) }
        } else emptyList()
        val online = runCatching { Geocoder.search(query, 8, near?.lat, near?.lon) }
            .getOrDefault(emptyList())
            .map { Suggestion(it.name, it.lat, it.lon, fromHistory = false) }
        fun shortKey(s: Suggestion) = s.label.substringBefore(",").trim().lowercase()
        fun distKm(s: Suggestion) =
            if (near != null && s.lat != null && s.lon != null) NavGeo.haversineKm(near.lat, near.lon, s.lat, s.lon)
            else Double.MAX_VALUE
        // Dedup by short name, but keep the NEAREST instance of each — so "Super U" resolves to the
        // store you're next to, not a farther namesake that merely ranked first (which used to get
        // dropped). 3× "Avenue de l'Ange Gardien" still collapses to the closest one.
        val best = LinkedHashMap<String, Suggestion>()
        for (s in (local + hist + poi + online)) {
            val k = shortKey(s)
            val cur = best[k]
            if (cur == null || distKm(s) < distKm(cur)) best[k] = s
        }
        val merged = best.values.toList()
        // FOCUS ON THE CLOSEST: rank by distance from the user (a namesake 1000 km away must never
        // beat the one you're standing at). Coordless history names sink to the end.
        return if (near != null) merged.sortedBy { distKm(it) }.take(8) else merged.take(8)
    }

    /** A POI was tapped on the map → show the directions card (unless mid-trip or already previewing). */
    fun onMapPoiTapped(name: String, lat: Double, lon: Double) {
        if (active.value != null || _state.value.previewing) return
        _state.value = _state.value.copy(tappedPlace = TappedPoi(name, lat, lon))
    }

    /** Empty-map tap (or Close on the card) → dismiss the tapped-POI card. */
    fun dismissTappedPlace() {
        if (_state.value.tappedPlace != null) _state.value = _state.value.copy(tappedPlace = null)
    }

    /**
     * External deep-link entry: another app (e.g. MikeShopping) launched us with a destination.
     * Preview a route to (lat, lon) — the user confirms with Start, exactly like a tapped POI.
     */
    fun navigateTo(name: String, lat: Double, lon: Double) {
        chooseSuggestion(Suggestion(name.ifBlank { "Destination" }, lat, lon, fromHistory = false))
    }

    /** "Directions" on the tapped-POI card → preview a route to it (reuses the search preview flow). */
    fun directionsToTappedPlace() {
        val t = _state.value.tappedPlace ?: return
        _state.value = _state.value.copy(tappedPlace = null)
        chooseSuggestion(Suggestion(t.name, t.lat, t.lon, fromHistory = false))
    }

    /** Pick a suggestion → preview a route to it (using its coords, or re-geocoding a history hit). */
    fun chooseSuggestion(s: Suggestion) {
        if (active.value != null) {
            _state.value = _state.value.copy(notice = "A trip is already active. End it first.")
            return
        }
        suggestJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            if (s.lat != null && s.lon != null) {
                enterPreview(s.label, s.lat, s.lon)
            } else {
                val place = trips.geocode(s.label) ?: run {
                    _state.value = _state.value.copy(busy = false, notice = "Couldn't find \"${s.label}\".")
                    return@launch
                }
                enterPreview(place.name, place.lat, place.lon)
            }
        }
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
        suggestJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Finding $dest…")
            val place = trips.geocode(dest)
            if (place == null) {
                _state.value = _state.value.copy(busy = false, notice = "Couldn't find \"$dest\".")
                return@launch
            }
            enterPreview(place.name, place.lat, place.lon)
        }
    }

    /**
     * Route from the current daemon fix to (lat,lon) and enter PREVIEW (framed, not started). Clears
     * the search field + suggestions so the next destination starts blank. Shared by [preview] and
     * [chooseSuggestion]. Assumes busy=true was already set by the caller.
     */
    private suspend fun enterPreview(name: String, lat: Double, lon: Double) {
        _state.value = _state.value.copy(notice = "Reading your location…")
        val fix = trips.currentFix()
        if (fix == null) {
            _state.value = _state.value.copy(busy = false, notice = "No location fix from the daemon (GPS provider may be down).")
            return
        }
        _state.value = _state.value.copy(notice = "Routing…")
        val route = trips.route(fix.lat, fix.lon, lat, lon)
        if (route == null) {
            _state.value = _state.value.copy(busy = false, notice = "Couldn't compute a route to \"$name\".")
            return
        }
        val points = runCatching { PolylineCodec.decode(route.polyline) }.getOrDefault(emptyList())
        pendingPlace = Geocoder.Place(name, lat, lon)
        pendingFix = fix
        pendingRoute = route
        resetGuidanceTrackers()
        val typed = _state.value.query.ifBlank { name }
        val near = _location.value
        _state.value = _state.value.copy(
            busy = false,
            notice = null,
            query = "",
            suggestions = emptyList(),
            routePoints = points,
            routeSteps = route.steps,
            routeKm = route.km,
            routeEtaMin = route.etaMin,
            destName = name,
            previewing = true,
            nearby = emptyList(), nearbyOpen = false,   // clear stale Explore pins on a new route
        )
        // Cache the chosen place offline + log the choice (detached, best-effort).
        viewModelScope.launch {
            runCatching { PlacesRepo.save(getApplication(), name, lat, lon) }
            runCatching {
                trips.logSearch(
                    query = typed, results = null,
                    chosenLabel = name, chosenLat = lat, chosenLon = lon,
                    nearLat = near?.lat, nearLon = near?.lon,
                )
            }
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
                notice = if (id != null) null else "Trip created but the cloud didn't confirm it.",
            )
        }
    }

    /** Discard the previewed route (back to the map). */
    fun cancelPreview() {
        pendingPlace = null; pendingFix = null; pendingRoute = null
        _guidance.value = null
        _state.value = _state.value.copy(
            previewing = false,
            query = "",
            suggestions = emptyList(),
            routePoints = emptyList(),
            routeSteps = emptyList(),
            routeKm = null,
            routeEtaMin = null,
            destName = null,
            notice = null,
            nearby = emptyList(), nearbyOpen = false,
        )
    }

    // ---- EXPLORE NEARBY (🔍) — parking/fuel/EV around the destination (or you) ----------------

    /**
     * Search for **parking + fuel + EV** around the most relevant anchor: the destination if one is
     * set (active trip, or a previewed route), else the user's current position. The common case is
     * "I've routed to a venue but need the car park" — so parking is ranked first. Opens the sheet
     * immediately (with a spinner) and fills it when Overpass answers.
     */
    fun exploreNearby() {
        if (active.value == null && pendingPlace == null && _location.value == null) {
            _state.value = _state.value.copy(notice = "No location yet — can't search nearby.")
            return
        }
        val hasRoute = active.value != null || pendingPlace != null
        _state.value = _state.value.copy(nearbyOpen = true, nearbyHasRoute = hasRoute)
        loadNearby(if (hasRoute) "dest" else "you")
    }

    /**
     * Load the Explore sheet for a mode: "dest" (parking/POIs AROUND the destination), "route" (strung
     * ALONG the road ahead, drive-past order), or "you" (around the current position).
     */
    fun loadNearby(mode: String) {
        val a = active.value
        val pp = pendingPlace
        val loc = _location.value
        val anchorName = when {
            mode == "route" -> "your route"
            mode == "you" -> "you"
            a != null -> a.destName
            pp != null -> pp.name
            else -> "there"
        }
        _state.value = _state.value.copy(
            nearbyMode = mode, nearbyBusy = true, nearbyAnchor = anchorName, nearby = emptyList(),
        )
        viewModelScope.launch {
            val results = when (mode) {
                "route" -> {
                    val pts = _state.value.routePoints
                    if (loc != null && pts.size >= 2) NearbySearch.searchAlongRoute(pts, loc.lat, loc.lon)
                    else emptyList()
                }
                "you" -> if (loc != null) NearbySearch.search(loc.lat, loc.lon, radiusM = 500) else emptyList()
                else -> {   // "dest"
                    val dLat = a?.destLat ?: pp?.lat
                    val dLon = a?.destLon ?: pp?.lon
                    if (dLat != null && dLon != null) NearbySearch.search(dLat, dLon, radiusM = 400) else emptyList()
                }
            }
            // Ignore a stale result (sheet dismissed, or the user switched mode meanwhile).
            if (!_state.value.nearbyOpen || _state.value.nearbyMode != mode) return@launch
            _state.value = _state.value.copy(nearbyBusy = false, nearby = results)
        }
    }

    /** Pick a nearby result (usually a car park) → route there instead. Re-previews from the fix. */
    fun chooseNearby(p: NearbySearch.Place) {
        _state.value = _state.value.copy(nearbyOpen = false)
        if (active.value != null) {
            // Mid-drive re-routing to a new destination is a bigger change — keep it explicit for now.
            _state.value = _state.value.copy(notice = "End the trip first to navigate to ${p.name}.")
            return
        }
        suggestJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Routing to ${p.name}…")
            enterPreview(p.name, p.lat, p.lon)
        }
    }

    fun dismissNearby() {
        _state.value = _state.value.copy(nearbyOpen = false)
    }

    /** End the active trip (POST end + broadcast trip.ended). */
    fun endTrip() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Ending trip…")
            val s = trips.endTrip()
            _state.value = _state.value.copy(
                busy = false,
                notice = if (s != null) "Trip ended: ${"%.0f".format(s.durationMin)} min, ${s.sampleCount} samples." else "Ended locally (cloud unconfirmed).",
                // Clear the route + the search field once the trip is done (next entry starts blank).
                query = "",
                suggestions = emptyList(),
                routePoints = emptyList(),
                routeSteps = emptyList(),
                routeKm = null,
                routeEtaMin = null,
                destName = null,
                nearby = emptyList(), nearbyOpen = false,
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
        private const val SUGGEST_DEBOUNCE_MS = 350L
        private const val ARRIVE_M = 40.0      // auto-end the trip within this of the destination
        private const val OFF_ROUTE_M = 70.0   // reroute when the cross-track distance exceeds this
        private const val OFF_ROUTE_TICKS = 4  // …for this many consecutive polls (~12s) — avoids noise
        private const val SPEED_EMA_ALPHA = 0.08  // long-window speed average → stable ETA
        private const val ETA_EMA_ALPHA = 0.15    // extra smoothing on the ETA itself
    }
}
