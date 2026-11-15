package dev.remotescreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Result of an install started by the remote shell, or the system's last word. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system has queued its install dialog for us to start.
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            return
        }

        val name = intent.getStringExtra("name") ?: "the apk"
        val ok = status == PackageInstaller.STATUS_SUCCESS
        Notifications.result(
            context,
            if (ok) "Installed" else "Install failed",
            if (ok) "$name is installed" else "$name: ${message.ifBlank { "status $status" }}"
        )
    }
}
