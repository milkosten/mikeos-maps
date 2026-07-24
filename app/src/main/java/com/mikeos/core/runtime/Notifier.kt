package com.mikeos.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notification plumbing shared by the runtime: the foreground-service notification
 * and the `notify(text)` universal skill's user-facing alerts.
 *
 * Kept dependency-light (uses the app's default launcher icon) so any consuming app
 * works without supplying assets.
 */
object Notifier {
    private const val SERVICE_CHANNEL = "mikeagent.heartbeat"
    private const val ALERT_CHANNEL = "mikeagent.alerts"
    private var alertId = 1000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                "Agent heartbeat",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Keeps the resident agent alive." }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Agent alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Messages the agent wants you to see." }
        )
    }

    /** The persistent notification the foreground service must post. */
    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, SERVICE_CHANNEL)
            .setContentTitle("MikeAgent")
            .setContentText(text)
            .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** Fire a user-facing alert (the `notify` skill). Returns a short status string. */
    fun alert(ctx: Context, text: String): String {
        ensureChannels(ctx)
        if (ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "notify skipped: no POST_NOTIFICATIONS permission"
        }
        val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
            .setContentTitle("MikeAgent")
            .setContentText(text)
            .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(ctx).notify(alertId++, n)
            "notified user"
        }.getOrElse { "notify failed: ${it.message}" }
    }
}
