package com.mikeos.maps.trips

import android.content.Context
import android.util.Log
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.hive.HiveIdentity
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.net.Geocoder
import com.mikeos.maps.net.TripsCloudClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The heart of MikeMaps: the DETERMINISTIC trip lifecycle. It does NOT rely on the LLM picking
 * a skill — routing, trip creation, per-beat sampling, and end-of-trip are all direct actions
 * wired here and driven by the heartbeat + the UI.
 *
 * Lifecycle:
 *  • [route]        — geocode is done by the caller; this wraps trips-cloud `/api/route`.
 *  • [startTrip]    — create the trip in trips-cloud, keep the trip_id, broadcast `trip.started`.
 *  • [beatSample]   — called on every heartbeat while a trip is ACTIVE: read the daemon fix,
 *                     compute speed, POST one sample, and (throttled ~60s) broadcast `trip.progress`.
 *  • [endTrip]      — POST end, broadcast `trip.ended`.
 *
 * Hive broadcasts go through the installed [MikeAgent]'s [com.mikeos.core.hive.HiveSocket] with
 * the exact type strings the charter/EVENTS.md define: `trip.started`, `trip.progress`, `trip.ended`.
 *
 * X-API-KEY is the app's hive agent key (from the installed MikeAgent, or the persisted
 * credentials file after §0 self-registration). device_id is the agent's hive name.
 */
class TripManager private constructor(private val appContext: Context) {

    private val cloud = TripsCloudClient()
    private val identity = HiveIdentity("MikeMaps", BuildConfig.DAEMON_BASE_URL)

    /** Public, observable state of the active trip (null = no active trip). */
    data class ActiveTrip(
        val tripId: String,
        val destName: String,
        val destLat: Double,
        val destLon: Double,
        val km: Double,
        val etaMin: Double,
        val polyline: String,
        val startedAtMs: Long,
        val samplesPosted: Int = 0,
        val distanceSoFarKm: Double = 0.0,
        val lastSpeedKmh: Double? = null,
        val lastLat: Double? = null,
        val lastLon: Double? = null,
    )

    private val _active = MutableStateFlow<ActiveTrip?>(null)
    val active: StateFlow<ActiveTrip?> = _active.asStateFlow()

    // Serialize lifecycle transitions so a heartbeat sample can't race start/end.
    private val lock = Mutex()

    // High-cadence (5s) sampler: while a trip is active it reads the daemon fix and posts to
    // trips-cloud every 5s (12×/min) so we bank fine-grained speed data for the congestion model.
    // Replaces the old once-per-60s-beat sampling. Runs on its own scope for the trip's lifetime.
    private val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var samplerJob: Job? = null

    // trip.progress throttle — broadcast at most ~once/60s.
    @Volatile private var lastProgressBroadcastMs = 0L

    // Running average speed accumulation (for the end summary).
    @Volatile private var speedSum = 0.0
    @Volatile private var speedCount = 0

    /** The user-scoped hive agent key used as X-API-KEY, or null before self-registration. */
    fun apiKey(): String? =
        MikeAgent.get()?.cred?.agentKey ?: identity.load(appContext)?.agentKey

    /** The agent's hive name — used as device_id on trips. */
    private fun deviceId(): String? =
        MikeAgent.get()?.cred?.name ?: identity.load(appContext)?.name

    // ---- ROUTE -----------------------------------------------------------------------------

    /** Wrap trips-cloud `/api/route` (keyless). */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TripsCloudClient.Route? =
        cloud.route(fromLat, fromLon, toLat, toLon)

    /** Geocode a destination name -> coords (Nominatim). */
    suspend fun geocode(name: String): Geocoder.Place? = Geocoder.geocode(name)

    /** Read the current daemon fix (the ONE shared location). */
    suspend fun currentFix(): DaemonLocation.Fix? = DaemonLocation.current()

    // ---- START -----------------------------------------------------------------------------

    /**
     * Create a trip in trips-cloud from an already-computed route + origin, keep the trip_id,
     * and broadcast `trip.started`. Returns the trip_id (never-trust-200: verified) or null.
     */
    suspend fun startTrip(
        destName: String,
        destLat: Double, destLon: Double,
        originLat: Double, originLon: Double,
        route: TripsCloudClient.Route,
    ): String? = lock.withLock {
        val key = apiKey() ?: run { Log.w(TAG, "startTrip: no api key yet"); return null }
        val tripId = cloud.createTrip(
            apiKey = key,
            destName = destName,
            destLat = destLat, destLon = destLon,
            originLat = originLat, originLon = originLon,
            polyline = route.polyline,
            km = route.km, etaMin = route.etaMin,
            deviceId = deviceId(),
        ) ?: run { Log.w(TAG, "startTrip: createTrip returned no trip_id (silent-drop guard tripped)"); return null }

        speedSum = 0.0; speedCount = 0
        lastProgressBroadcastMs = 0L
        _active.value = ActiveTrip(
            tripId = tripId,
            destName = destName,
            destLat = destLat, destLon = destLon,
            km = route.km, etaMin = route.etaMin,
            polyline = route.polyline,
            startedAtMs = System.currentTimeMillis(),
            lastLat = originLat, lastLon = originLon,
        )
        Log.i(TAG, "trip started: $tripId -> $destName (${route.km} km, ${route.etaMin} min)")

        // Kick off the 5s cloud sampler (samples immediately, then every 5s until the trip ends).
        startSampler()

        // Broadcast trip.started so Guide/Storyteller/Sound react.
        broadcast(
            "trip.started",
            JSONObject()
                .put("trip_id", tripId)   // consumers (Guide->route.pois, Storyteller) correlate on this
                .put("dest", destName)
                .put("eta_min", route.etaMin)
                .put("km", route.km)
                .put("polyline", route.polyline)
                .put("mode", "driving")
                .toString(),
        )
        tripId
    }

    // ---- SAMPLE (per beat, deterministic) --------------------------------------------------

    /**
     * Called on every heartbeat while a trip is active. Reads the daemon fix, computes speed,
     * POSTs exactly one sample (ISO-8601 ts), and — throttled ~once/60s — broadcasts
     * `trip.progress`. Best-effort; never throws. No-op if no active trip.
     */
    suspend fun beatSample() {
        val current = _active.value ?: return
        val key = apiKey() ?: return
        val fix = DaemonLocation.current() ?: run {
            Log.i(TAG, "beatSample: no daemon fix (GPS provider may be down) — skipping this beat")
            return
        }
        val speed = fix.speedKmh ?: 0.0

        val stored = cloud.postSamples(
            key, current.tripId,
            listOf(TripsCloudClient.Sample(fix.lat, fix.lon, speed, fix.ts)),
        )
        if (stored <= 0) {
            Log.w(TAG, "beatSample: sample not stored (never-trust-200 guard)")
        }

        // Update running state + avg accumulation.
        if (fix.speedKmh != null) { speedSum += speed; speedCount++ }
        val addedKm = if (current.lastLat != null && current.lastLon != null)
            haversineKm(current.lastLat, current.lastLon, fix.lat, fix.lon) else 0.0
        _active.value = current.copy(
            samplesPosted = current.samplesPosted + (if (stored > 0) stored else 0),
            distanceSoFarKm = current.distanceSoFarKm + addedKm,
            lastSpeedKmh = fix.speedKmh,
            lastLat = fix.lat, lastLon = fix.lon,
        )

        // NOTE: we intentionally do NOT broadcast `trip.progress` on the hive. The trail is
        // persisted to trips-cloud via the sample POST above, and no agent consumes per-beat
        // location — broadcasting it to all ~11 siblings every beat was pure hive spam. Trip
        // lifecycle stays on the hive via trip.started / trip.ended only.
    }

    /** Start the 5s sampler loop: sample now, then every [SAMPLE_INTERVAL_MS] while the trip lives. */
    private fun startSampler() {
        samplerJob?.cancel()
        samplerJob = samplerScope.launch {
            while (isActive && _active.value != null) {
                runCatching { beatSample() }.onFailure { Log.w(TAG, "sampler tick failed: ${it.message}") }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /** Stop the 5s sampler (trip ending / ended). */
    private fun stopSampler() {
        samplerJob?.cancel()
        samplerJob = null
    }

    // ---- END -------------------------------------------------------------------------------

    /**
     * End the active trip: POST end, broadcast `trip.ended`, clear active state. Returns the
     * summary or null. Best-effort.
     */
    suspend fun endTrip(): TripsCloudClient.TripSummary? = lock.withLock {
        val current = _active.value ?: return null
        stopSampler()
        val key = apiKey() ?: run { _active.value = null; return null }
        val avg = if (speedCount > 0) speedSum / speedCount else null
        val summary = cloud.endTrip(key, current.tripId, avgKmh = avg)
        if (summary == null) {
            Log.w(TAG, "endTrip: end returned no trip_id (never-trust-200 guard)")
        } else {
            Log.i(TAG, "trip ended: ${summary.tripId} (${summary.durationMin} min, ${summary.sampleCount} samples)")
            broadcast(
                "trip.ended",
                JSONObject()
                    .put("trip_id", summary.tripId)
                    .put("duration_min", summary.durationMin)
                    .put("km", current.km)
                    .put("avg_kmh", summary.avgKmh ?: avg ?: 0.0)
                    .toString(),
            )
        }
        _active.value = null
        summary
    }

    // ---- reads for UI + skills -------------------------------------------------------------

    suspend fun recentTrips(limit: Int = 20): List<TripsCloudClient.Trip> {
        val key = apiKey() ?: return emptyList()
        return cloud.recentTrips(key, limit)
    }

    suspend fun getTrip(tripId: String): TripsCloudClient.Trip? {
        val key = apiKey() ?: return null
        return cloud.getTrip(key, tripId)
    }

    suspend fun congestion(lat: Double, lon: Double, radiusM: Int = 1500, hour: Int? = null): TripsCloudClient.Congestion? {
        val key = apiKey() ?: return null
        return cloud.congestion(key, lat, lon, radiusM, hour)
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Broadcast a hive event via the installed MikeAgent's socket. Best-effort. */
    private suspend fun broadcast(type: String, body: String) {
        val socket = MikeAgent.get()?.hiveSocket ?: run {
            Log.w(TAG, "broadcast($type): hive socket unavailable"); return
        }
        runCatching {
            val n = socket.broadcast(type, body)
            Log.i(TAG, "broadcast $type to $n sibling(s)")
        }.onFailure { Log.w(TAG, "broadcast $type failed: ${it.message}") }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val TAG = "TripManager"
        private const val PROGRESS_THROTTLE_MS = 60_000L
        private const val SAMPLE_INTERVAL_MS = 5_000L   // 5s high-cadence cloud sampling while driving

        @Volatile private var instance: TripManager? = null
        fun get(context: Context): TripManager =
            instance ?: synchronized(this) {
                instance ?: TripManager(context.applicationContext).also { instance = it }
            }
    }
}
