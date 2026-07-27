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
    private const val MAX_FRAMES = 400   // ring buffer (~80 MB at ~200 KB/frame); oldest deleted (no upload in P1)

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null), "mikestreet").apply { mkdirs() }

    /** A fresh session dir per capture run (roughly per drive). */
    fun newSession(context: Context): File {
        val id = Instant.ofEpochMilli(System.currentTimeMillis()).toString().replace(":", "-")
        return File(root(context), id).apply { mkdirs() }
    }

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

    /** Keep only the newest [MAX_FRAMES] image+json pairs (delete oldest). */
    fun enforceCap(session: File) {
        val frames = session.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: return
        val over = frames.size - MAX_FRAMES
        if (over <= 0) return
        frames.take(over).forEach { jpg ->
            jpg.delete()
            File(jpg.parentFile, jpg.nameWithoutExtension + ".gps.json").delete()
        }
    }
}
