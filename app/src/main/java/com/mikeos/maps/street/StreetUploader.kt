package com.mikeos.maps.street

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mikeos.maps.BuildConfig
import com.mikeos.maps.net.Doh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads completed MikeStreet sessions to the self-hosted lake (`street-api.osmike.com` — the
 * `mikeos-street` service on the 242 box, all frames stored on its 117 TB RAID6).
 *
 * - **WiFi-preferred:** full-12 MP frames are ~4 MB, so by default we only upload on an unmetered
 *   network — never hammer cellular. (Caller can override for a manual "upload now".)
 * - **Resumable:** the lake is content-addressed (sha256) and idempotent, so a re-run only sends what's
 *   missing; an interrupted upload just continues next time.
 * - **Self-reclaiming:** a fully-uploaded session is marked (`.uploaded`) so [StreetStore]'s storage GC
 *   evicts it first, keeping un-synced full-res frames on the phone until they're safe in the lake.
 *
 * TLS: `street-api.osmike.com` has a valid public cert (Caddy/Let's Encrypt) → standard OkHttp + DoH
 * (this ROM's system DNS is flaky), never the loopback trust-all client.
 */
object StreetUploader {

    private const val TAG = "StreetUploader"
    private val mutex = Mutex()   // one upload pass at a time

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(Doh.dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    private val jpegMedia = "image/jpeg".toMediaType()
    private val jsonMedia = "application/json".toMediaType()

    /**
     * On WiFi (or Ethernet) — NOT cellular. We used to require an *unmetered* network, but that quietly
     * blocked uploads forever on a **metered WiFi hotspot** (the owner's phone tethers a metered "…mobile"
     * WiFi, so NET_CAPABILITY_NOT_METERED is false → every drive stayed stranded). The real intent is
     * "don't chew mobile data with full-res frames": upload on any WiFi/Ethernet, skip on cellular.
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Upload every completed session (all but [activeSession], still being written). Returns the number
     * of frames newly stored in the lake. [requireWifi] gates on WiFi/Ethernet (default true).
     */
    suspend fun uploadPending(
        context: Context,
        activeSession: File? = null,
        requireWifi: Boolean = true,
    ): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!MikeStreet.isEnabled(context)) return@withContext 0
            if (requireWifi && !isOnWifi(context)) { Log.i(TAG, "skip upload: not on WiFi (won't use mobile data)"); return@withContext 0 }

            // Naming-agnostic: upload ANY session dir that has frames and no `.uploaded` marker — never
            // keyed on the dir-name shape (bare-timestamp vs trip-<id>_...), so a naming change can't
            // silently strand drives again.
            val sessions = StreetStore.root(context).listFiles { f -> f.isDirectory }?.sortedBy { it.name }
                ?: return@withContext 0
            var stored = 0
            var failed = 0
            for (sess in sessions) {
                if (sess == activeSession) continue
                if (File(sess, ".uploaded").exists()) continue
                if (requireWifi && !isOnWifi(context)) { Log.w(TAG, "WiFi dropped mid-run — stopping"); break }
                val frames = sess.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
                    ?.sortedBy { it.name } ?: continue
                if (frames.isEmpty()) continue
                var allOk = true
                for (jpg in frames) {
                    if (uploadFrame(jpg)) stored++ else { allOk = false; failed++ }
                }
                if (allOk) {
                    StreetStore.markUploaded(sess)   // safe to reclaim locally
                    Log.i(TAG, "uploaded session ${sess.name} (${frames.size} frames)")
                } else {
                    Log.w(TAG, "session ${sess.name} incomplete — NOT marked uploaded, will retry")
                }
            }
            if (stored > 0 || failed > 0) Log.i(TAG, "upload pass done: $stored stored, $failed failed")
            stored
        }
    }

    /** POST one frame + its gps.json sidecar. Idempotent server-side (dedupes by sha256). */
    private fun uploadFrame(jpg: File): Boolean {
        return try {
            val json = File(jpg.parentFile, jpg.nameWithoutExtension + ".gps.json")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", jpg.name, jpg.asRequestBody(jpegMedia))
                .apply { if (json.exists()) addFormDataPart("meta", json.name, json.asRequestBody(jsonMedia)) }
                .build()
            val req = Request.Builder()
                .url("${BuildConfig.STREET_API_URL}/api/frames")
                .header("Authorization", "Bearer ${BuildConfig.STREET_INGEST_KEY}")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                // never-trust-200: only count it stored if the lake says so
                val ok = resp.isSuccessful && payload.contains("\"stored\":true")
                if (!ok) Log.w(TAG, "frame ${jpg.name} -> ${resp.code} ${payload.take(160)}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload ${jpg.name} failed: ${e.message}")
            false
        }
    }
}
