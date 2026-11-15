package dev.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.io.File

object Notifications {

    const val CHANNEL_SESSION = "session"
    const val CHANNEL_APPROVAL = "approval"

    const val ID_MIRROR = 4101
    const val ID_VIRTUAL = 4102
    const val ID_APPROVAL = 4103
    const val ID_SHELL = 4104
    const val ID_REQUEST = 4105
    const val ID_APK = 4106

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Active remote sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always visible while the screen is being shared or the shell is open."
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

    private fun openApp(ctx: Context, requestCode: Int, extra: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.extra()
        return PendingIntent.getActivity(ctx, requestCode, intent, flags())
    }

    fun session(ctx: Context, title: String, text: String, stopIntent: Intent): Notification {
        ensureChannels(ctx)
        val stop = PendingIntent.getService(ctx, 2, stopIntent, flags())
        return Notification.Builder(ctx, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, 1))
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
            .setSmallIcon(R.drawable.ic_stat_bridge)
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

    /** A receiver asked the phone to start mirroring; the owner taps to allow capture. */
    fun mirrorRequest(ctx: Context) {
        ensureChannels(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = openApp(ctx, 22) { putExtra(MainActivity.EXTRA_MIRROR, true) }
        manager.notify(
            ID_REQUEST,
            Notification.Builder(ctx, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle("Start screen mirroring?")
                .setContentText("A paired receiver asked to see this screen. Tap to allow capture.")
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "A paired receiver asked to see this screen. Android asks you to allow " +
                            "capture every time; tap here to show that dialog."
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    /** An APK arrived over the shell; tapping installs it through Android's own dialog. */
    fun apkReady(ctx: Context, file: File) {
        ensureChannels(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = openApp(ctx, 23) { putExtra(MainActivity.EXTRA_APK, file.absolutePath) }
        manager.notify(
            ID_APK,
            Notification.Builder(ctx, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle("APK received")
                .setContentText("${file.name} (${file.length() / 1024} KB) — tap to install")
                .setStyle(Notification.BigTextStyle().bigText("${file.name}\nTap to open Android's install dialog."))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    /** Outcome of an install started from the remote shell. */
    fun result(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ID_APK,
            Notification.Builder(ctx, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, 1))
                .build()
        )
    }
}
