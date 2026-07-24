package com.mikeos.maps.agent

import android.content.Context
import android.util.Log
import com.mikeos.core.MikeAgentConfig
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.agent.Skill
import com.mikeos.core.agent.Soul
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.trips.TripManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the shared MikeAgent runtime (vendored under `com.mikeos.core`) into MikeMaps.
 *
 * MikeMaps's agent is Mike's **navigation + road-sensing agent**. Its PRIMARY job is to get
 * Mike where he's going and record the drive: route A→B, create a trip, sample the speed/GPS
 * trail on every beat, and announce the journey on the hive so Guide/Storyteller/Sound compose
 * on it.
 *
 * The trail recording is DETERMINISTIC — it runs on the heartbeat via [TripManager.beatSample]
 * (hooked off the perceptionProvider), NOT by hoping the LLM picks a skill (house rule: proactive
 * features must be deterministic on the beat). The skills below let the brain answer Q&A:
 *  • route(dest)        -> geocode + trips-cloud route (km + eta)
 *  • start_trip(dest)   -> route + create trip + broadcast trip.started
 *  • end_trip           -> end the active trip + broadcast trip.ended
 *  • congestion(near)   -> the learned per-hour speed profile near a point
 * The four universal skills (hive_send / remember / recall / notify) are added by the runtime.
 */
object MapsMikeAgent {

    private const val TAG = "MapsMikeAgent"

    // Sibling agents a navigation agent naturally collaborates with on this phone.
    private val SIBLINGS = listOf("MikeGuide", "MikeStoryteller", "MikeSound", "MikeMind")

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val trips = TripManager.get(app)

        val soul = Soul(
            agentName = "Maps",
            appName = "MikeMaps",
            persona = "I'm MikeMaps's agent — Mike's navigator and road sensor. My job is to get " +
                "Mike where he's going and to record the drive: I route A→B for free, I log the " +
                "speed and GPS trail of every trip, and I announce the journey on the hive so the " +
                "other agents (Guide, Storyteller, Sound) can compose on it. Every drive makes the " +
                "next ETA and the congestion map more true.",
            goals = listOf(
                "Get Mike from A to B with a route, distance, and a live ETA — for free (OSRM)",
                "Record the speed + GPS trail of every drive to trips-cloud (≥1 sample per beat while moving)",
                "Announce each trip on the hive (trip.started / trip.progress / trip.ended) so siblings compose on it",
                "Turn the trail history into a congestion model so I can answer 'how's traffic on my usual route'",
            ),
        )

        val skills = buildSkills(trips)

        // Per-beat perception: this ALSO drives the deterministic trail recording. While a trip
        // is active, every beat reads the daemon fix, posts a sample, and (throttled) broadcasts
        // trip.progress — no LLM decision required. The returned string is the perception the
        // brain sees.
        HeartbeatService.perceptionProvider = {
            val a = trips.active.value
            if (a != null) {
                runCatching { trips.beatSample() }
                    .onFailure { Log.w(TAG, "beatSample failed: ${it.message}") }
                val cur = trips.active.value
                if (cur != null) {
                    "Active trip to ${cur.destName}: ${"%.1f".format(cur.km)} km planned, " +
                        "ETA ${"%.0f".format(cur.etaMin)} min. So far ${"%.1f".format(cur.distanceSoFarKm)} km, " +
                        "${cur.samplesPosted} samples recorded, current speed " +
                        (cur.lastSpeedKmh?.let { "${"%.0f".format(it)} km/h" } ?: "unknown") + "."
                } else "Trip just ended; idle."
            } else {
                "No active trip. Ready to route Mike somewhere and record the drive."
            }
        }

        bg.launch {
            runCatching {
                MikeAgent.install(
                    app,
                    MikeAgentConfig(
                        daemonToken = BuildConfig.DAEMON_TOKEN,
                        userName = "Mike",
                        siblings = SIBLINGS,
                    ),
                    soul,
                    skills,
                )
                HeartbeatService.start(app)
                MikeAgent.get()?.connectHive()
                Log.i(TAG, "MikeAgent installed; heartbeat + hive started (siblings=$SIBLINGS)")
            }.onFailure { Log.w(TAG, "MikeAgent install failed: ${it.message}") }
        }
    }

    // ---- SKILLS (wrap MikeMaps's REAL navigation functions) --------------------------------

    private fun buildSkills(trips: TripManager): List<Skill> = listOf(
        Skill(
            name = "route",
            description = "Compute a driving route to a named destination (e.g. 'Nice city center'): " +
                "geocodes the name, reads Mike's current location from the daemon, and returns the " +
                "distance in km and the ETA in minutes. Use this to answer 'how far / how long to X'.",
            paramsSchema = """{"dest":"the destination name or address to route to"}""",
            run = { args ->
                val dest = args.optString("dest")
                if (dest.isBlank()) return@Skill "route needs a dest"
                val place = trips.geocode(dest) ?: return@Skill "Couldn't find a place called '$dest'."
                val fix = trips.currentFix() ?: return@Skill "No current location fix from the daemon right now."
                val r = trips.route(fix.lat, fix.lon, place.lat, place.lon)
                    ?: return@Skill "Couldn't compute a route to '$dest'."
                "Route to ${place.name}: ${"%.1f".format(r.km)} km, ETA ${"%.0f".format(r.etaMin)} min."
            },
        ),
        Skill(
            name = "start_trip",
            description = "Start navigating to a named destination and begin recording the drive: " +
                "geocode + route + create the trip in trips-cloud + announce trip.started on the hive. " +
                "Use this when Mike says he's heading somewhere.",
            paramsSchema = """{"dest":"the destination name or address to drive to"}""",
            run = { args ->
                val dest = args.optString("dest")
                if (dest.isBlank()) return@Skill "start_trip needs a dest"
                if (trips.active.value != null) return@Skill "A trip is already active to ${trips.active.value?.destName}. End it first."
                val place = trips.geocode(dest) ?: return@Skill "Couldn't find a place called '$dest'."
                val fix = trips.currentFix() ?: return@Skill "No current location fix from the daemon — can't start."
                val r = trips.route(fix.lat, fix.lon, place.lat, place.lon)
                    ?: return@Skill "Couldn't compute a route to '$dest'."
                val id = trips.startTrip(place.name, place.lat, place.lon, fix.lat, fix.lon, r)
                    ?: return@Skill "Failed to create the trip in trips-cloud."
                "Trip started to ${place.name} (${"%.1f".format(r.km)} km, ETA ${"%.0f".format(r.etaMin)} min). trip_id=$id. Announced trip.started."
            },
        ),
        Skill(
            name = "end_trip",
            description = "End the currently active trip: finalise it in trips-cloud and announce " +
                "trip.ended on the hive. Use when Mike has arrived or stopped driving.",
            paramsSchema = """{}""",
            run = {
                val a = trips.active.value ?: return@Skill "No active trip to end."
                val s = trips.endTrip() ?: return@Skill "Tried to end the trip but the cloud didn't confirm it."
                "Ended trip to ${a.destName}: ${"%.0f".format(s.durationMin)} min, ${s.sampleCount} samples" +
                    (s.avgKmh?.let { ", avg ${"%.0f".format(it)} km/h" } ?: "") + "."
            },
        ),
        Skill(
            name = "congestion",
            description = "Look up the learned traffic/congestion profile near a named place — the " +
                "average speed and, if available, how it varies by hour of day. Answers 'how's traffic " +
                "on my usual route' or 'is X slow at 18:00'. Uses the trail history MikeMaps has recorded.",
            paramsSchema = """{"near":"a place name to check congestion around","hour":"optional hour of day 0-23"}""",
            run = { args ->
                val near = args.optString("near")
                if (near.isBlank()) return@Skill "congestion needs a 'near' place"
                val place = trips.geocode(near) ?: return@Skill "Couldn't find a place called '$near'."
                val hour = args.optString("hour").toIntOrNull()
                val c = trips.congestion(place.lat, place.lon, hour = hour)
                    ?: return@Skill "No congestion data near ${place.name} yet."
                if (c.sampleCount == 0) return@Skill "No trail samples recorded near ${place.name} yet."
                val avg = c.avgKmh?.let { "avg ${"%.0f".format(it)} km/h" } ?: "avg unknown"
                val byHour = if (c.byHour.isNotEmpty())
                    " By hour: " + c.byHour.toSortedMap().entries.joinToString(", ") { "${it.key}h=${"%.0f".format(it.value)}" }
                else ""
                "Near ${place.name}: $avg over ${c.sampleCount} samples.$byHour"
            },
        ),
    )
}
