package dev.remotescreen

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * What this install is allowed to do, and how to get more.
 *
 * There are exactly three levels an ordinary app can reach:
 *
 *  - **app**: everything the SDK lets a normal app do (mirroring, input, the
 *    shell, app lists, killing background processes, rotation, APK push).
 *  - **device owner**: one command from an authorised computer
 *    (`dpm set-device-owner`) unlocks hiding packages and rebooting.
 *  - **root**: `su` unlocks everything, force-stop included.
 *
 * Android's own wireless-debugging pairing cannot be reached from inside an
 * app: Android 11+ pairs a debugging *host* over TLS with a SPAKE2 key
 * exchange, implemented only in Android's BoringSSL, and the endpoint further
 * requires the client certificate that pairing produced. So this app cannot
 * pair with its own adbd. The iPhone receiver can, because it links a port of
 * AOSP's adb that carries the pairing client and BoringSSL; it reaches that
 * through this phone's `wireless` and `adbports` commands. Wireless debugging
 * therefore gives *this* app no shell — root and device owner stay the doors on
 * this side, and the receiver's adb is the third door.
 */
object Access {

    enum class Level(val label: String) {
        APP("app only"),
        OWNER("device owner"),
        ROOT("root")
    }

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootValue = false

    /** Cached because the first `su` probe can wait for the root manager's prompt. */
    fun hasRoot(ctx: Context): Boolean {
        if (rootChecked) return rootValue
        rootValue = ShellEngine(ctx).hasRoot()
        rootChecked = true
        return rootValue
    }

    fun resetRoot() {
        rootChecked = false
        rootValue = false
    }

    fun isDeviceOwner(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(ctx.packageName)
    }

    fun isAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching { dpm.isAdminActive(admin(ctx)) }.getOrDefault(false)
    }

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun level(ctx: Context): Level = when {
        hasRoot(ctx) -> Level.ROOT
        isDeviceOwner(ctx) -> Level.OWNER
        else -> Level.APP
    }

    /** The one command, typed on a computer that is already allowed to debug this phone. */
    fun deviceOwnerCommand(ctx: Context): String =
        "adb shell dpm set-device-owner ${ctx.packageName}/${AdminReceiver::class.java.name}"

    fun summary(ctx: Context): String {
        val level = level(ctx)
        val owner = isDeviceOwner(ctx)
        val root = hasRoot(ctx)
        return buildString {
            append("access   ${level.label}\n")
            append("root     ${if (root) "yes" else "no"}\n")
            append("owner    ${if (owner) "device owner" else "no"}\n")
            append("admin    ${if (isAdmin(ctx)) "yes" else "no"}\n")
            append("settings ${if (android.provider.Settings.System.canWrite(ctx)) "writable" else "read-only"}\n")
            append("install  ${if (ctx.packageManager.canRequestPackageInstalls()) "allowed" else "blocked"}\n")
            when (level) {
                Level.ROOT -> append("# root: force-stop, disable, uninstall and reboot all work\n")
                Level.OWNER -> append("# device owner: hide/unhide packages and reboot work; force-stop still needs root\n")
                Level.APP -> append(
                    "# app only: mirroring, input, apps, kill, rotate, volume, APK push.\n" +
                        "# disable and reboot need root, or a device-owner install:\n" +
                        "#   ${deviceOwnerCommand(ctx)}\n"
                )
            }
        }
    }
}
