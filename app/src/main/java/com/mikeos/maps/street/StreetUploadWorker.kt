package com.mikeos.maps.street

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mikeos.maps.trips.TripManager

/**
 * Durable MikeStreet upload drain. Runs under WorkManager (survives app background/death, Doze, and
 * process kills) so completed drives always reach the lake — the fix for trips getting stranded when
 * the old opportunistic, Activity-lifecycle upload path ended mid-run with no retry.
 *
 * Contract: **drain, don't sample** — [StreetUploader.uploadPending] flushes EVERY pending trip; if any
 * remain un-`.uploaded` (an error, or WiFi dropped), we return [Result.retry] so WorkManager re-runs us
 * with exponential backoff until the backlog is empty. WiFi-gated (full-res frames never touch cellular).
 * Idempotent end-to-end (server dedupes by sha256), so aggressive retry is free.
 */
class StreetUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!MikeStreet.isEnabled(ctx)) return Result.success()
        // WiFi/Ethernet only — don't chew mobile data with ~4 MB frames. Not on WiFi yet → back off.
        if (!StreetUploader.isOnWifi(ctx)) return Result.retry()

        // Never upload/mark the session still being written for the active trip.
        val active = runCatching {
            val tripId = TripManager.get(ctx as Application).active.value?.tripId
            StreetStore.activeSession(ctx, tripId)
        }.getOrNull()

        runCatching { StreetUploader.uploadPending(ctx, activeSession = active, requireWifi = true) }
        val backlog = StreetUploader.pendingCount(ctx, active)
        Log.i(TAG, "drain pass complete — backlog now $backlog trip(s)")
        // Keep going until every completed trip is flushed.
        return if (backlog > 0) Result.retry() else Result.success()
    }

    private companion object { const val TAG = "StreetUploadWorker" }
}
