package com.mikeos.core.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mikeos.core.agent.MikeAgent
import java.util.concurrent.TimeUnit

/**
 * MIKEAGENT.md §1c/§1d — the DORMANT heartbeat.
 *
 * WorkManager periodic work (min interval 15 min) that ticks [MikeAgent.beat] while
 * the app is backgrounded and the foreground [HeartbeatService] isn't running.
 * Battery-friendly and survives process death. It does a one-off socket read of unread
 * so hive messages are still drained even without a live socket.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Notifier.ensureChannels(applicationContext)
        val agent = MikeAgent.get() ?: return Result.success()
        return runCatching {
            // No live socket in doze — pull unread over REST so the inbox drains.
            agent.hiveSocket?.readUnread()
            val perception = HeartbeatService.perceptionProvider?.invoke() ?: ""
            // Dormant beat runs only every ~15 min, so always force a full reason cycle —
            // this is the agent's proactive "think even if nothing changed" tick.
            agent.beat(perception, force = true)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "mikeagent.heartbeat.periodic"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, work,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
