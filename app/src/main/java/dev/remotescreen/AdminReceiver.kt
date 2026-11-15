package dev.remotescreen

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device administrator component. It only exists so the owner can use the
 * standard Android admin actions (lock now, remote wipe request) and so a
 * device-owner install can expose the wireless debugging toggle. It never
 * blocks uninstall and never hides itself.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device administrator enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device administrator disabled", Toast.LENGTH_SHORT).show()
    }
}
