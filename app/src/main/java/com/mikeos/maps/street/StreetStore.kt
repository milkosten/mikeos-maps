package com.mikeos.maps.street

import android.content.Context
import android.util.Log
import com.mikeos.maps.net.DaemonLocation
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * On-device frame storage for MikeStreet (P1: local only, size-capped ring buffer). Files live under
 * app-private external storage (no permission), inspectable via
 * `adb pull …/Android/data/com.mikeos.maps/files/mikestreet`.
 */
object StreetStore {

    private const val TAG = "StreetStore"
    // Full-12MP frames are ~4 MB each (user's choice — keep native resolution). We no longer cap by
    // frame COUNT (a per-session count would delete the start of a long single-trip drive); instead the
    // whole store has a byte ceiling, and the GC prefers already-uploaded sessions so un-uploaded frames
    // are never evicted until the hard ceiling is hit.
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024   // 20 GB rolling ceiling on-device
    private const val UPLOADED_MARKER = ".uploaded"

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null), "mikestreet").apply { mkdirs() }

    /**
     * One session dir per trip (roughly per drive). Named `trip-<id8>_<iso>` when there's an active
     * trip, else `drive_<iso>` — so all frames of a drive group together and are easy to backfill.
     */
    fun newSession(context: Context, tripId: String? = null): File {
        val stamp = Instant.ofEpochMilli(System.currentTimeMillis()).toString().replace(":", "-")
        val name = if (!tripId.isNullOrBlank()) "trip-${tripId.take(8)}_$stamp" else "drive_$stamp"
        return File(root(context), name).apply { mkdirs() }
    }

    /** The session dir currently being written for [tripId] (its `trip-<id8>` prefix), or null — so a
     *  background upload pass never touches / prematurely marks the drive still in progress. */
    fun activeSession(context: Context, tripId: String?): File? {
        if (tripId.isNullOrBlank()) return null
        val prefix = "trip-${tripId.take(8)}"
        return root(context).listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }?.maxByOrNull { it.name }
    }

    /** Mark a session fully uploaded to the lake — the GC evicts these first. */
    fun markUploaded(session: File) = runCatching { File(session, UPLOADED_MARKER).writeText("1") }
    private fun isUploaded(session: File) = File(session, UPLOADED_MARKER).exists()

    fun frameFile(session: File, ts: Long): File = File(session, "frame_$ts.jpg")

    /** Write `frame_<ts>.gps.json` next to the image: the Fix + the FULL raw daemon location. */
    fun writeGps(
        session: File, ts: Long, fix: DaemonLocation.Fix,
        raw: JSONObject?, tripId: String?, deviceId: String,
    ) {
        val j = JSONObject()
        runCatching {
            j.put("ts_capture", Instant.ofEpochMilli(ts).toString())
            j.put("lat", fix.lat); j.put("lon", fix.lon)
            fix.speedKmh?.let { j.put("speed_kmh", it); j.put("speed_ms", it / 3.6) }
            fix.bearing?.let { j.put("heading_deg", it) }
            j.put("session", session.name)
            j.put("device_id", deviceId)
            tripId?.let { j.put("trip_id", it) }
            raw?.let { j.put("daemon_raw", it) }   // altitude, accuracy, satellites, city… — as much GPS as the daemon has
            File(session, "frame_$ts.gps.json").writeText(j.toString())
        }.onFailure { Log.w(TAG, "gps.json write failed: ${it.message}") }
    }

    /**
     * Keep the whole store under [MAX_TOTAL_BYTES]. Delete oldest sessions first, but prefer sessions
     * already uploaded to the lake — so a full-res drive that hasn't synced yet is preserved until the
     * hard ceiling forces a trim. Never deletes the newest session (the one likely being written).
     */
    fun enforceGlobalCap(context: Context) {
        val sessions = root(context).listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.toMutableList() ?: return
        if (sessions.size <= 1) return
        val newest = sessions.removeAt(sessions.lastIndex)   // protect the current/most-recent session
        var total = dirSize(newest) + sessions.sumOf { dirSize(it) }
        if (total <= MAX_TOTAL_BYTES) return
        // Pass 1: evict oldest UPLOADED sessions. Pass 2: if still over, evict oldest regardless.
        for (uploadedOnly in booleanArrayOf(true, false)) {
            for (s in sessions.toList()) {
                if (total <= MAX_TOTAL_BYTES) return
                if (uploadedOnly && !isUploaded(s)) continue
                val freed = dirSize(s)
                if (s.deleteRecursively()) { total -= freed; sessions.remove(s) }
            }
        }
        if (total > MAX_TOTAL_BYTES) Log.w(TAG, "still over cap after GC: ${total / (1024 * 1024)} MB")
    }

    private fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
}
