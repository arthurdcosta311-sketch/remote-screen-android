package dev.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifications {

    const val CHANNEL_SESSION = "session"
    const val CHANNEL_APPROVAL = "approval"

    const val ID_MIRROR = 4101
    const val ID_VIRTUAL = 4102
    const val ID_APPROVAL = 4103

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Active remote sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always visible while the screen is being shared."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "Connection approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you to allow or deny an incoming receiver."
                enableVibration(true)
            }
        )
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun session(ctx: Context, title: String, text: String, stopIntent: Intent): Notification {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
        val stop = PendingIntent.getService(ctx, 2, stopIntent, flags())
        return Notification.Builder(ctx, CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stop
                ).build()
            )
            .build()
    }

    /** Session that is waiting for the device owner to allow it. */
    fun approval(
        ctx: Context,
        title: String,
        text: String,
        approveIntent: Intent,
        denyIntent: Intent
    ): Notification {
        ensureChannels(ctx)
        val approve = PendingIntent.getBroadcast(ctx, 11, approveIntent, flags())
        val deny = PendingIntent.getBroadcast(ctx, 12, denyIntent, flags())
        return Notification.Builder(ctx, CHANNEL_APPROVAL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.checkbox_on_background),
                    "Allow",
                    approve
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    "Deny",
                    deny
                ).build()
            )
            .build()
    }
}
