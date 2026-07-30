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
import java.time.Instant

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
        val tripId: String,            // STABLE LOCAL id ("local-…") — used for the hive + street frames, works offline
        val cloudTripId: String? = null,  // trips-cloud id, filled once the trip is reconciled online (null = offline/pending)
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

    // The app's live (smoothed) ETA estimate, pushed in from the ViewModel each tick and logged
    // with every 5s sample so we can analyse / improve the ETA model later.
    @Volatile var lastEtaMin: Double? = null
    @Volatile var lastRemainingKm: Double? = null

    // Serialize lifecycle transitions so a heartbeat sample can't race start/end.
    private val lock = Mutex()

    // High-cadence (5s) sampler: while a trip is active it reads the daemon fix and posts to
    // trips-cloud every 5s (12×/min) so we bank fine-grained speed data for the congestion model.
    // Replaces the old once-per-60s-beat sampling. Runs on its own scope for the trip's lifetime.
    private val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var samplerJob: Job? = null

    // OFFLINE-FIRST: a trip starts LOCALLY with no cloud id (no internet / not yet registered). Samples
    // buffer here; a background reconciler creates the cloud trip and flushes the buffer once online.
    private val bufferLock = Any()
    private val sampleBuffer = ArrayDeque<TripsCloudClient.Sample>()
    @Volatile private var pendingCreate: CreateArgs? = null   // args to create the cloud trip (until reconciled)
    @Volatile private var pendingEnd: Double? = null          // avg_kmh to post on end, if the trip ended before reconciling
    @Volatile private var reconcilerJob: Job? = null

    /** Everything trips-cloud needs to create the trip record later, once we're online. */
    private data class CreateArgs(
        val destName: String, val destLat: Double, val destLon: Double,
        val originLat: Double, val originLon: Double,
        val polyline: String, val km: Double, val etaMin: Double,
    )

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
    suspend fun geocode(name: String): Geocoder.Place? {
        val near = runCatching { currentFix() }.getOrNull()   // bias to a local match over a far namesake
        return Geocoder.geocode(name, near?.lat, near?.lon)
    }

    /** Read the current daemon fix (the ONE shared location). */
    suspend fun currentFix(): DaemonLocation.Fix? = DaemonLocation.current()

    // ---- START -----------------------------------------------------------------------------

    /**
     * Start a trip. OFFLINE-FIRST: the trip begins IMMEDIATELY with a stable local id — no internet, no
     * cloud trip_id, and no self-registration are required (a person about to drive out of a dead zone
     * must be able to start, and offline maps make a cloud id impossible to require anyway). The cloud
     * record is created + samples flushed in the background by [startReconciler] the moment we're online.
     * Always returns the local id (never null).
     */
    suspend fun startTrip(
        destName: String,
        destLat: Double, destLon: Double,
        originLat: Double, originLon: Double,
        route: TripsCloudClient.Route?,   // may be null: "Start anyway" before the route/GPS is ready
    ): String = lock.withLock {
        // A trip only needs a destination — the route can be empty and fill in on the first beat.
        val poly = route?.polyline ?: ""
        val km = route?.km ?: 0.0
        val etaMin = route?.etaMin ?: 0.0
        val localId = "local-${System.currentTimeMillis()}"

        speedSum = 0.0; speedCount = 0
        lastProgressBroadcastMs = 0L
        synchronized(bufferLock) { sampleBuffer.clear() }
        pendingEnd = null
        pendingCreate = CreateArgs(destName, destLat, destLon, originLat, originLon, poly, km, etaMin)
        _active.value = ActiveTrip(
            tripId = localId,
            cloudTripId = null,
            destName = destName,
            destLat = destLat, destLon = destLon,
            km = km, etaMin = etaMin,
            polyline = poly,
            startedAtMs = System.currentTimeMillis(),
            lastLat = originLat, lastLon = originLon,
        )
        Log.i(TAG, "trip started (local): $localId -> $destName ($km km, $etaMin min${if (route == null) ", route pending" else ""})")

        // Kick off the 5s sampler (buffers offline) and the reconciler (creates the cloud trip when online).
        startSampler()
        startReconciler()

        // Broadcast trip.started on the hive (loopback → works offline) with the STABLE local id, so
        // Guide/Storyteller correlate on it and the matching trip.ended carries the same id.
        broadcast(
            "trip.started",
            JSONObject()
                .put("trip_id", localId)
                .put("dest", destName)
                .put("dest_lat", destLat)     // so Guide/Storyteller can locate the destination immediately
                .put("dest_lon", destLon)
                .put("origin_lat", originLat)
                .put("origin_lon", originLon)
                .put("eta_min", etaMin)
                .put("km", km)
                .put("polyline", poly)
                .put("mode", "driving")
                .toString(),
        )
        localId
    }

    // ---- SAMPLE (per beat, deterministic) --------------------------------------------------

    /**
     * Called on every heartbeat while a trip is active. Reads the daemon fix, computes speed,
     * POSTs exactly one sample (ISO-8601 ts), and — throttled ~once/60s — broadcasts
     * `trip.progress`. Best-effort; never throws. No-op if no active trip.
     */
    suspend fun beatSample() {
        val current = _active.value ?: return
        val fix = DaemonLocation.current() ?: run {
            Log.i(TAG, "beatSample: no daemon fix (GPS provider may be down) — skipping this beat")
            return
        }
        val speed = fix.speedKmh ?: 0.0

        // ALWAYS record the sample locally first (offline-safe). It uploads now if we're online +
        // reconciled, else it waits in the buffer for the reconciler to flush it.
        synchronized(bufferLock) {
            sampleBuffer.addLast(TripsCloudClient.Sample(fix.lat, fix.lon, speed, fix.ts, lastEtaMin, lastRemainingKm))
            while (sampleBuffer.size > MAX_BUFFERED_SAMPLES) sampleBuffer.removeFirst()   // ~days of driving; safety cap
        }

        // Update running state + avg accumulation (independent of whether the cloud has it yet).
        if (fix.speedKmh != null) { speedSum += speed; speedCount++ }
        val addedKm = if (current.lastLat != null && current.lastLon != null)
            haversineKm(current.lastLat, current.lastLon, fix.lat, fix.lon) else 0.0
        _active.value = current.copy(
            distanceSoFarKm = current.distanceSoFarKm + addedKm,
            lastSpeedKmh = fix.speedKmh,
            lastLat = fix.lat, lastLon = fix.lon,
        )

        tryFlush(current.cloudTripId)   // drain the buffer to the cloud if we have an id + connectivity

        // NOTE: we intentionally do NOT broadcast `trip.progress` on the hive — no agent consumes
        // per-beat location; the trail is persisted via the sample POST. Lifecycle stays on the hive
        // via trip.started / trip.ended only.
    }

    /** Drain the buffered samples to trips-cloud, if we have a cloud trip id + an api key. Best-effort. */
    private suspend fun tryFlush(cloudId: String?) {
        if (cloudId == null) return
        val key = apiKey() ?: return
        val batch = synchronized(bufferLock) { sampleBuffer.toList() }
        if (batch.isEmpty()) return
        val stored = cloud.postSamples(key, cloudId, batch)
        if (stored > 0) {
            synchronized(bufferLock) { repeat(minOf(stored, sampleBuffer.size)) { sampleBuffer.removeFirst() } }
            _active.value = _active.value?.let { it.copy(samplesPosted = it.samplesPosted + stored) }
        } else {
            Log.w(TAG, "tryFlush: ${batch.size} sample(s) not stored yet — keeping buffered")
        }
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

    /**
     * Background reconciler: while a trip has no cloud id (started offline / before self-registration),
     * keep trying to CREATE it in trips-cloud. On success, flush the buffered samples. If the trip has
     * already ENDED offline ([pendingEnd] set), also post the end. Survives for the app's lifetime; exits
     * once there's nothing left to reconcile. (App-kill-while-offline persistence is a follow-up.)
     */
    private fun startReconciler() {
        if (reconcilerJob?.isActive == true) return
        reconcilerJob = samplerScope.launch {
            while (isActive) {
                val args = pendingCreate
                val active = _active.value
                val needsCreate = args != null && (active == null || active.cloudTripId == null)
                if (needsCreate) {
                    val key = apiKey()
                    if (key != null && args != null) {
                        val cloudId = cloud.createTrip(
                            apiKey = key,
                            destName = args.destName, destLat = args.destLat, destLon = args.destLon,
                            originLat = args.originLat, originLon = args.originLon,
                            polyline = args.polyline, km = args.km, etaMin = args.etaMin,
                            deviceId = deviceId(),
                        )
                        if (cloudId != null) {
                            pendingCreate = null
                            _active.value?.let { _active.value = it.copy(cloudTripId = cloudId) }
                            Log.i(TAG, "trip reconciled → cloud $cloudId; flushing ${sampleBuffer.size} buffered sample(s)")
                            tryFlush(cloudId)
                            // Ended before reconciling? (pendingEnd set by endTrip) finish it now.
                            val avg = pendingEnd
                            if (avg != null) {
                                tryFlush(cloudId)                       // any tail samples
                                cloud.endTrip(key, cloudId, avgKmh = avg)
                                pendingEnd = null
                                Log.i(TAG, "offline trip finalised in cloud: $cloudId")
                            }
                        }
                    }
                }
                // Nothing left to do → stop the reconciler.
                if (pendingCreate == null && pendingEnd == null &&
                    (_active.value == null || _active.value?.cloudTripId != null)) return@launch
                delay(RECONCILE_INTERVAL_MS)
            }
        }
    }

    // ---- END -------------------------------------------------------------------------------

    /**
     * End the active trip: POST end, broadcast `trip.ended`, clear active state. Returns the
     * summary or null. Best-effort.
     */
    suspend fun endTrip(): TripsCloudClient.TripSummary? = lock.withLock {
        val current = _active.value ?: return null
        stopSampler()
        val avg = if (speedCount > 0) speedSum / speedCount else null
        val durationMin = ((System.currentTimeMillis() - current.startedAtMs) / 60_000.0)

        // Broadcast trip.ended on the hive (loopback → offline-safe) with the STABLE local id — the same
        // id siblings saw at trip.started, whether or not the cloud knows about it yet.
        broadcast(
            "trip.ended",
            JSONObject()
                .put("trip_id", current.tripId)
                .put("duration_min", durationMin)
                .put("km", current.km)
                .put("avg_kmh", avg ?: 0.0)
                .toString(),
        )
        _active.value = null

        val cloudId = current.cloudTripId
        val key = apiKey()
        if (cloudId != null && key != null) {
            // Reconciled + online: flush the tail, then post end now.
            tryFlush(cloudId)
            val summary = cloud.endTrip(key, cloudId, avgKmh = avg)
            if (summary != null) {
                pendingCreate = null; pendingEnd = null
                Log.i(TAG, "trip ended: ${summary.tripId} (${summary.durationMin} min, ${summary.sampleCount} samples)")
                return summary
            }
            Log.w(TAG, "endTrip: end POST failed — reconciler will finish it")
        }
        // Not reconciled yet (offline the whole drive), or the end POST failed → hand off to the
        // reconciler: once online it creates the cloud trip, flushes the buffer, and posts the end.
        pendingEnd = avg ?: 0.0
        startReconciler()
        null
    }

    // ---- reads for UI + skills -------------------------------------------------------------

    suspend fun recentTrips(limit: Int = 20): List<TripsCloudClient.Trip> {
        val key = apiKey() ?: return emptyList()
        return cloud.recentTrips(key, limit)
    }

    /** Log a destination search to trips-cloud (best-effort; no-op without a key). */
    suspend fun logSearch(
        query: String,
        results: Int?,
        chosenLabel: String? = null,
        chosenLat: Double? = null,
        chosenLon: Double? = null,
        nearLat: Double? = null,
        nearLon: Double? = null,
    ) {
        val key = apiKey() ?: return
        cloud.logSearch(key, query, results, chosenLabel, chosenLat, chosenLon, nearLat, nearLon)
    }

    /**
     * Batch-log map interactions to trips-cloud. Returns rows stored, or -1 if there's no api key
     * yet (caller re-queues for the next flush). Best-effort background telemetry.
     */
    suspend fun logInteractions(events: List<TripsCloudClient.Interaction>): Int {
        val key = apiKey() ?: return -1
        return cloud.postInteractions(key, deviceId(), events)
    }

    suspend fun getTrip(tripId: String): TripsCloudClient.Trip? {
        val key = apiKey() ?: return null
        return cloud.getTrip(key, tripId)
    }

    suspend fun congestion(lat: Double, lon: Double, radiusM: Int = 1500, hour: Int? = null): TripsCloudClient.Congestion? {
        val key = apiKey() ?: return null
        return cloud.congestion(key, lat, lon, radiusM, hour)
    }

    // ---- SAVED PLACES ----------------------------------------------------------------------

    /**
     * Announce on the hive (via MikeAgent) that Mike ⭐-saved a place, so siblings react:
     * MikeGuide pre-warms POIs around it, MikeStoryteller seeds a story, MikeMind remembers it.
     * Best-effort — matches the `place.saved` contract documented in each app's from_mikemaps.md.
     */
    suspend fun announcePlaceSaved(
        label: String, shortName: String, lat: Double, lon: Double, kind: String,
    ) {
        broadcast(
            "place.saved",
            JSONObject()
                .put("label", label)
                .put("short_name", shortName)
                .put("lat", lat)
                .put("lon", lon)
                .put("kind", kind)
                .put("saved_at", Instant.now().toString())
                .toString(),
        )
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
        private const val RECONCILE_INTERVAL_MS = 8_000L    // retry creating the cloud trip while offline
        private const val MAX_BUFFERED_SAMPLES = 100_000    // ~5.8 days at 5s — safety cap on the offline buffer

        @Volatile private var instance: TripManager? = null
        fun get(context: Context): TripManager =
            instance ?: synchronized(this) {
                instance ?: TripManager(context.applicationContext).also { instance = it }
            }
    }
}
