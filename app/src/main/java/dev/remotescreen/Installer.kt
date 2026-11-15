package dev.remotescreen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * Installs an APK that arrived over the remote shell. Android always shows its
 * own install dialog for a normal app, so the receiver's push ends with one tap
 * on the phone; the APK waits in the app's cache until then.
 */
object Installer {

    /** Posts the "tap to install" notification for a freshly received APK. */
    fun request(ctx: Context, file: File) {
        Notifications.apkReady(ctx, file)
    }

    /** Starts Android's installer for the file. Call from a visible activity. */
    fun install(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        runCatching { startSession(ctx, file) }.onFailure { error ->
            Notifications.result(ctx, "Install failed", error.message ?: "could not start the installer")
        }
    }

    private fun startSession(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setSize(file.length()) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ctx, InstallReceiver::class.java).putExtra("name", file.name)
            val pending = PendingIntent.getBroadcast(
                ctx,
                sessionId,
                result,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pending.intentSender)
        }
    }
}
