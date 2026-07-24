package com.mikeos.core.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mikeos.core.agent.MikeAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MIKEAGENT.md §1c/§1d — the ACTIVE heartbeat.
 *
 * A generic ("specialUse") foreground service that:
 *  • holds the persistent hive [com.mikeos.core.hive.HiveSocket] open, and
 *  • ticks [MikeAgent.beat] every 60s while the app is alive.
 *
 * The app supplies the per-beat perception via [PerceptionProvider] set on
 * [MikeAgent.install]-adjacent config; here we default to an empty perception if the
 * app hasn't registered one, so the socket still stays open and inbound messages still
 * drive beats.
 */
class HeartbeatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notifier.serviceNotification(this, "Resident agent is awake")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SERVICE_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SERVICE_ID, notif)
        }

        // Hold the live hive connection open for the lifetime of the service.
        MikeAgent.get()?.connectHive()

        if (loop == null) loop = startLoop()
        return START_STICKY
    }

    private fun startLoop(): Job = scope.launch {
        var beatCount = 0L
        while (isActive) {
            val agent = MikeAgent.get()
            if (agent != null) {
                runCatching {
                    val perception = perceptionProvider?.invoke() ?: ""
                    // Change-gated most beats (skip the GPU when nothing changed), but FORCE a
                    // full reason cycle every FORCE_EVERY_BEATS so proactive agents still act on
                    // a schedule (suggest lists, price-check, restock) even on a static perception.
                    // Otherwise a quiet app looks dead between changes. The model still returns
                    // done:true fast when there's nothing to do, so an idle forced beat is cheap.
                    val force = beatCount > 0 && beatCount % FORCE_EVERY_BEATS == 0L
                    agent.beat(perception, force = force)
                }
            }
            beatCount++
            delay(HEARTBEAT_MS)
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SERVICE_ID = 4243
        private const val HEARTBEAT_MS = 60_000L
        // Force a full (non-gated) reason cycle this often, so a foregrounded agent keeps
        // doing proactive work even when its perception is unchanged (~10 min at 60s beats).
        private const val FORCE_EVERY_BEATS = 10L

        /**
         * Optional per-beat perception source the app registers. Kept as a simple
         * lambda so the app doesn't need to subclass anything. If null, beats run with
         * empty perception (still driven by the hive inbox).
         */
        @Volatile
        var perceptionProvider: (suspend () -> String)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HeartbeatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartbeatService::class.java))
        }
    }
}
