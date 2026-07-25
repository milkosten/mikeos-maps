package com.mikeos.maps.net

import android.content.Context
import android.util.Log
import com.mikeos.maps.trips.TripManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Non-invasive, fire-and-forget map-usage analytics.
 *
 * Records taps + pans/zooms into a BOUNDED in-memory queue — [tap]/[move] are O(1), run on the
 * caller's thread (typically the UI thread) and NEVER touch the network, so the user notices nothing.
 * A single background coroutine (IO dispatcher) flushes the queue to trips-cloud every [FLUSH_MS], or
 * eagerly once it reaches [BATCH]. Everything is best-effort: on any failure the batch is dropped and
 * the queue is capped at [MAX_QUEUE] (oldest dropped first) so analytics can never grow memory, block,
 * or interrupt the app. If there's no API key yet (early boot) the batch is re-queued for next time.
 */
object MapAnalytics {
    private const val TAG = "MapAnalytics"
    private const val MAX_QUEUE = 500     // hard bound on buffered events — drop oldest beyond this
    private const val BATCH = 40          // flush eagerly once this many are queued
    private const val DRAIN = 200         // max rows per POST
    private const val FLUSH_MS = 20_000L  // periodic background flush cadence

    private val queue = ConcurrentLinkedQueue<TripsCloudClient.Interaction>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null

    /** Start the background flusher once (idempotent). Safe to call from any screen / repeatedly. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (started.compareAndSet(false, true)) {
            scope.launch {
                while (true) {
                    delay(FLUSH_MS)
                    runCatching { flush() }.onFailure { Log.w(TAG, "flush error: ${it.message}") }
                }
            }
        }
    }

    /** Record a tap. [detail] is "poi" or "empty"; [name] is the tapped label for POI taps. */
    fun tap(lat: Double, lon: Double, detail: String, name: String?, zoom: Double?) =
        enqueue(TripsCloudClient.Interaction("tap", detail, lat, lon, zoom, null, name, now()))

    /** Record the end of a pan/zoom gesture — the resting camera center + zoom + bearing. */
    fun move(lat: Double, lon: Double, zoom: Double, bearing: Double) =
        enqueue(TripsCloudClient.Interaction("move", null, lat, lon, zoom, bearing, null, now()))

    private fun now() = System.currentTimeMillis()

    private fun enqueue(e: TripsCloudClient.Interaction) {
        queue.offer(e)
        while (queue.size > MAX_QUEUE) queue.poll()          // stay bounded — drop oldest
        if (queue.size >= BATCH) scope.launch { runCatching { flush() } }
    }

    private suspend fun flush() {
        val ctx = appContext ?: return
        if (queue.isEmpty()) return
        val batch = ArrayList<TripsCloudClient.Interaction>(DRAIN)
        while (batch.size < DRAIN) { val e = queue.poll() ?: break; batch.add(e) }
        if (batch.isEmpty()) return
        val stored = TripManager.get(ctx).logInteractions(batch)
        if (stored < 0) {
            // No API key yet (early boot) — put them back for the next cycle, still bounded.
            batch.forEach { queue.offer(it) }
            while (queue.size > MAX_QUEUE) queue.poll()
        }
    }
}
