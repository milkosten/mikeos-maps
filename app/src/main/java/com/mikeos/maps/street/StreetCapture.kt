package com.mikeos.maps.street

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mikeos.maps.net.DaemonLocation
import com.mikeos.maps.trips.TripManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * MikeStreet dashboard capture (P1), bound to the (foreground) Activity lifecycle. While the opt-in is
 * ON and the phone is moving, grabs a JPEG every second via CameraX [ImageCapture] (no preview surface)
 * and writes it + a `.gps.json`, size-capped locally. No upload yet (P3).
 *
 * Foreground-only by design: Android restricts background camera, and the dashboard-nav case has the
 * map on screen anyway. Camera binds to the Activity lifecycle, so it auto-releases when backgrounded.
 */
class StreetCapture(private val activity: ComponentActivity) {

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var loopJob: Job? = null
    private var session: File? = null
    private var sessionTripId: String? = null   // the trip this session belongs to (one session per trip)
    private var lastCaptureAt: Long = 0L        // for the idle→new-session decision
    private var lastUploadAt: Long = 0L         // for throttling the lake sync while parked
    private var frameTick: Long = 0L            // for throttling the storage GC
    private val exec = Executors.newSingleThreadExecutor()
    private val deviceId: String by lazy {
        runCatching { Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull() ?: "device"
    }

    /**
     * The dashboard mount can be portrait or landscape; without this every frame is saved with a
     * "rotate me 90°" EXIF flag that naive consumers ignore (sideways images). Tracking device tilt and
     * feeding it to [ImageCapture.setTargetRotation] makes CameraX write the CORRECT orientation.
     */
    @Volatile private var targetRotation: Int = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = targetRotation
            }
        }
    }

    /** Called from the Activity's onResume — (re)start capture if opted-in + permitted. */
    fun onResume() {
        if (!MikeStreet.isEnabled(activity) || !hasCameraPermission()) { stop(); return }
        bindCamera()
        startLoop()
        // Opportunistic sync: if we're back on WiFi (e.g. parked at home), push any pending drives.
        activity.lifecycleScope.launch { runCatching { StreetUploader.uploadPending(activity, session) } }
    }

    fun onPause() = stop()

    /** Re-evaluate after the user toggles the setting or grants CAMERA while resumed. */
    fun refresh() = onResume()

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        if (imageCapture != null) return
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(targetRotation)
                .build()
            try {
                p.unbindAll()
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                provider = p
                imageCapture = ic
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
                Log.i(TAG, "camera bound for MikeStreet capture")
            } catch (e: Exception) {
                Log.w(TAG, "camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = activity.lifecycleScope.launch {
            while (isActive) {
                if (!MikeStreet.isEnabled(activity)) {
                    MikeStreet.setCapturing(false); session = null; sessionTripId = null; delay(2000); continue
                }
                val fix = DaemonLocation.current()
                val moving = (fix?.speedKmh ?: 0.0) >= MOVING_KMH
                val ic = imageCapture
                if (moving && ic != null && fix != null) {
                    val tripId = runCatching { TripManager.get(activity.application).active.value?.tripId }.getOrNull()
                    val now = System.currentTimeMillis()
                    // One session per trip: rotate only on a NEW trip or after a long idle (a genuinely new
                    // leg) — NOT on brief traffic-light stops, which previously fragmented one drive into
                    // dozens of sessions.
                    val newLeg = session == null || tripId != sessionTripId ||
                        (now - lastCaptureAt) > SESSION_IDLE_RESET_MS
                    if (newLeg) {
                        session = StreetStore.newSession(activity, tripId)
                        sessionTripId = tripId
                    }
                    lastCaptureAt = now
                    MikeStreet.setCapturing(true)
                    captureOne(ic, fix)
                    delay(ACTIVE_INTERVAL_MS)
                } else {
                    // Stopped (light/traffic/parked). Keep the session open so a brief stop stays in one
                    // drive; a long idle rotates the session on the next move (handled above). While
                    // stopped is also a good moment to sync completed drives to the lake (WiFi-gated, so
                    // it effectively fires when parked on WiFi, not mid-drive on cellular).
                    MikeStreet.setCapturing(false)
                    val now = System.currentTimeMillis()
                    if (now - lastUploadAt > UPLOAD_INTERVAL_MS) {
                        lastUploadAt = now
                        launch { runCatching { StreetUploader.uploadPending(activity, session) } }
                    }
                    delay(PROBE_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun captureOne(ic: ImageCapture, fix: DaemonLocation.Fix) {
        val sess = session ?: return
        val ts = System.currentTimeMillis()
        val jpg = StreetStore.frameFile(sess, ts)
        val raw = DaemonLocation.currentRaw()
        val tripId = sessionTripId
        ic.targetRotation = targetRotation   // ensure the frame's EXIF orientation matches the phone right now
        suspendCancellableCoroutine<Unit> { cont ->
            val opts = ImageCapture.OutputFileOptions.Builder(jpg).build()
            ic.takePicture(opts, exec, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    StreetStore.writeGps(sess, ts, fix, raw, tripId, deviceId)
                    // Global byte-ceiling instead of a per-session frame count: with one session per
                    // trip, a per-session cap would delete the START of a long drive. Only trims oldest
                    // (already-uploaded first) sessions when the whole store exceeds the ceiling.
                    frameTick++
                    if (frameTick % GC_EVERY_FRAMES == 0L) StreetStore.enforceGlobalCap(activity)
                    MikeStreet.bumpFrames()
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.w(TAG, "capture error: ${exc.message}")
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    private fun stop() {
        loopJob?.cancel(); loopJob = null
        MikeStreet.setCapturing(false)
        session = null
        sessionTripId = null
        runCatching { orientationListener.disable() }
        runCatching { provider?.unbindAll() }
        imageCapture = null
    }

    companion object {
        private const val TAG = "StreetCapture"
        private const val MOVING_KMH = 3.0        // walking pace and up
        private const val ACTIVE_INTERVAL_MS = 1000L
        private const val PROBE_INTERVAL_MS = 4000L
        private const val SESSION_IDLE_RESET_MS = 5 * 60_000L  // >5 min stopped ⇒ a new leg/session
        private const val GC_EVERY_FRAMES = 20L                // run the storage GC every N frames, not every frame
        private const val UPLOAD_INTERVAL_MS = 60_000L         // throttle the parked-idle lake sync
    }
}
