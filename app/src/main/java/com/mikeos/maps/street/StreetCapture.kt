package com.mikeos.maps.street

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
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
    private val exec = Executors.newSingleThreadExecutor()
    private val deviceId: String by lazy {
        runCatching { Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull() ?: "device"
    }

    /** Called from the Activity's onResume — (re)start capture if opted-in + permitted. */
    fun onResume() {
        if (!MikeStreet.isEnabled(activity) || !hasCameraPermission()) { stop(); return }
        bindCamera()
        startLoop()
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
                .build()
            try {
                p.unbindAll()
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                provider = p
                imageCapture = ic
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
                if (!MikeStreet.isEnabled(activity)) { MikeStreet.setCapturing(false); session = null; delay(2000); continue }
                val fix = DaemonLocation.current()
                val moving = (fix?.speedKmh ?: 0.0) >= MOVING_KMH
                val ic = imageCapture
                if (moving && ic != null && fix != null) {
                    if (session == null) session = StreetStore.newSession(activity)
                    MikeStreet.setCapturing(true)
                    captureOne(ic, fix)
                    delay(ACTIVE_INTERVAL_MS)
                } else {
                    // P1: no vision gate yet — idle until moving. (P2 adds the 60s-probe + road/trail check.)
                    MikeStreet.setCapturing(false)
                    session = null
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
        val tripId = runCatching { TripManager.get(activity.application).active.value?.tripId }.getOrNull()
        suspendCancellableCoroutine<Unit> { cont ->
            val opts = ImageCapture.OutputFileOptions.Builder(jpg).build()
            ic.takePicture(opts, exec, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    StreetStore.writeGps(sess, ts, fix, raw, tripId, deviceId)
                    StreetStore.enforceCap(sess)
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
        runCatching { provider?.unbindAll() }
        imageCapture = null
    }

    companion object {
        private const val TAG = "StreetCapture"
        private const val MOVING_KMH = 3.0        // walking pace and up
        private const val ACTIVE_INTERVAL_MS = 1000L
        private const val PROBE_INTERVAL_MS = 4000L
    }
}
