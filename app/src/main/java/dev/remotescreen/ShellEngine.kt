package dev.remotescreen

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * The command language behind the remote shell on port 7003.
 *
 * An ordinary app cannot host `adbd`, so this is an app-level shell: everything
 * that only needs app-level access works directly, and everything that needs
 * system rights (force-stop, disable a package, reboot) is attempted through
 * root, then through the device-owner APIs, and reported honestly when neither
 * is available. Nothing here skips a consent screen the phone would show.
 */
class ShellEngine(private val ctx: Context) {

    data class Result(val code: Int, val out: String, val err: String)

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootAvailable = false

    private var systemPackagesCache: Set<String>? = null

    fun run(line: String): Result {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result(0, HELP, "")
        val cmd = parts[0].lowercase()
        val args = parts.drop(1)
        val pkg = args.firstOrNull()
        return try {
            when (cmd) {
                "help" -> Result(0, HELP, "")
                "device" -> Result(0, device(), "")
                "apps" -> Result(0, apps(args), "")
                "ps" -> Result(0, processes(), "")
                "inspect" -> if (pkg == null) usage("inspect <package>") else Result(0, inspect(pkg), "")
                "start", "launch" -> if (pkg == null) usage("start <package>") else Result(0, start(pkg), "")
                "kill" -> if (pkg == null) usage("kill <package>") else Result(0, kill(pkg), "")
                "stop" -> if (pkg == null) usage("stop <package>") else Result(0, stop(pkg), "")
                "disable" -> if (pkg == null) usage("disable <package>") else Result(0, setEnabled(pkg, false), "")
                "enable" -> if (pkg == null) usage("enable <package>") else Result(0, setEnabled(pkg, true), "")
                "uninstall" -> if (pkg == null) usage("uninstall <package>") else Result(0, uninstall(pkg), "")
                "rotate" -> Result(0, rotate(pkg ?: "90"), "")
                "reboot" -> Result(0, reboot(pkg), "")
                "mirror" -> Result(0, mirror(), "")
                "lock" -> Result(0, lock(), "")
                "shell" -> shell(args.joinToString(" "))
                else -> Result(127, "", "unknown command '$cmd' — try help\n")
            }
        } catch (e: Exception) {
            Result(1, "", "${e.message ?: e.javaClass.simpleName}\n")
        }
    }

    private fun usage(text: String) = Result(64, "", "usage: $text\n")

    // ------------------------------------------------------------- inspection

    private fun device(): String {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val owner = when {
            dpm == null -> "none"
            dpm.isDeviceOwnerApp(ctx.packageName) -> "device owner"
            dpm.isAdminActive(admin()) -> "administrator"
            else -> "none"
        }
        return buildString {
            append("model    ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("abi      ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            append("uptime   ${SystemClock.elapsedRealtime() / 60_000} min\n")
            append("package  ${ctx.packageName}\n")
            append("root     ${if (hasRoot()) "yes" else "no"}\n")
            append("owner    $owner\n")
            append("input    ${if (TouchService.instance != null) "accessibility on" else "accessibility off"}\n")
            append("settings ${if (Settings.System.canWrite(ctx)) "writable" else "read-only"}\n")
            append("ports    pair ${Ports.PAIR} mirror ${Ports.MIRROR} second ${Ports.VIRTUAL} shell ${Ports.SHELL}\n")
        }
    }

    /** One line per package: pkg, label, version, uid, user|system, enabled|disabled. */
    private fun apps(args: List<String>): String {
        val pm = ctx.packageManager
        val which = args.getOrNull(0)?.lowercase() ?: "all"
        val filter = args.getOrNull(1)?.lowercase()
        val lines = mutableListOf<String>()
        for (info in pm.getInstalledPackages(0)) {
            val app = info.applicationInfo ?: continue
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (which == "user" && system) continue
            if (which == "system" && !system) continue
            val name = app.loadLabel(pm).toString().replace('\t', ' ').replace('\n', ' ')
            val pkg = info.packageName
            if (filter != null && !pkg.lowercase().contains(filter) && !name.lowercase().contains(filter)) continue
            lines += listOf(
                pkg,
                name,
                info.versionName ?: "-",
                app.uid.toString(),
                if (system) "system" else "user",
                if (isEnabled(pkg)) "enabled" else "disabled"
            ).joinToString("\t")
        }
        lines.sort()
        return "packages ${lines.size}\n" + lines.joinToString("\n") + "\n"
    }

    /** One line per process: pid, usr|system, name. */
    private fun processes(): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val systemPkgs = systemPackages()
        val rows = mutableMapOf<String, String>()

        for (p in am?.runningAppProcesses.orEmpty()) {
            val owner = p.processName.substringBefore(':')
            val kind = if (systemPkgs.contains(owner)) "system" else "usr"
            rows[p.processName] = "${p.pid}\t$kind\t${p.processName}"
        }

        // 'ps' sees only this app's processes on Android 9+ (hidepid); it is still
        // useful for an exact pid and user, so merge whatever it reports.
        val ps = runProcess(arrayOf("ps", "-A", "-o", "PID,USER,NAME"), 5_000)
        if (ps.first == 0) {
            for (raw in ps.second.lineSequence().drop(1)) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t.size < 3) continue
                val pid = t[0]
                val user = t[1]
                val name = t.subList(2, t.size).joinToString(" ")
                val kind = if (user.startsWith("u0_") || user == "shell") "usr" else user
                if (!rows.containsKey(name)) rows[name] = "$pid\t$kind\t$name"
            }
        }

        val sorted = rows.values.sortedBy { it.substringAfterLast('\t') }
        return buildString {
            append("processes ${sorted.size}\n")
            for (line in sorted) append(line).append('\n')
            append("# Android hides other apps' processes from ordinary apps.\n")
        }
    }

    private fun inspect(pkg: String): String {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val app = info.applicationInfo ?: return "no such package: $pkg\n"
        return buildString {
            append("package  ${info.packageName}\n")
            append("label    ${app.loadLabel(pm)}\n")
            append("version  ${info.versionName ?: "-"} (${info.longVersionCode})\n")
            append("uid      ${app.uid}\n")
            append("kind     ${if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "system" else "user"}\n")
            append("enabled  ${isEnabled(info.packageName)}\n")
            append("sdk      min ${app.minSdkVersion} / target ${app.targetSdkVersion}\n")
            append("apk      ${app.sourceDir}\n")
            append("data     ${app.dataDir}\n")
            val perms = info.requestedPermissions
            if (perms != null) {
                append("perms    ${perms.size}\n")
                for (perm in perms.sorted()) append("         $perm\n")
            }
        }
    }

    private fun isEnabled(pkg: String): Boolean = try {
        val pm = ctx.packageManager
        val state = pm.getApplicationEnabledSetting(pkg)
        state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (e: Exception) {
        false
    }

    private fun systemPackages(): Set<String> {
        systemPackagesCache?.let { return it }
        val set = mutableSetOf<String>()
        for (info in ctx.packageManager.getInstalledPackages(0)) {
            val app = info.applicationInfo
            if (app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                set.add(info.packageName)
            }
        }
        systemPackagesCache = set
        return set
    }

    // ------------------------------------------------------------------ actions

    private fun start(pkg: String): String {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "error: $pkg has no launcher activity\n"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "launched $pkg\n# Android 14 blocks app launches from a background service; " +
                "if nothing opened, unlock the phone and open the app there.\n"
        } catch (e: Exception) {
            "error: ${e.message}\n"
        }
    }

    private fun kill(pkg: String): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
            ?: return "error: no activity manager\n"
        am.killBackgroundProcesses(pkg)
        return "asked Android to kill the background processes of $pkg\n" +
            "# Apps in the foreground are not killed; use stop for that (root).\n"
    }

    private fun stop(pkg: String): String {
        val r = runProcess(arrayOf("su", "-c", "am force-stop $pkg"), 10_000)
        if (r.first == 0 && !r.second.contains("Error")) return "force-stopped $pkg\n"
        return "error: force-stop needs root — Android gives no ordinary app that power\n"
    }

    private fun setEnabled(pkg: String, enabled: Boolean): String {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(ctx.packageName) && dpm.isAdminActive(admin())) {
            val ok = runCatching { dpm.setApplicationHidden(admin(), pkg, !enabled) }.getOrDefault(false)
            if (ok) return if (enabled) "un-hid $pkg (device owner)\n" else "hid $pkg (device owner)\n"
        }
        val command = if (enabled) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
        val r = runProcess(arrayOf("su", "-c", command), 15_000)
        if (r.first == 0 && !r.second.contains("Error")) return r.second.trim() + "\n"
        return "error: needs device owner or root to ${if (enabled) "enable" else "disable"} $pkg\n"
    }

    private fun uninstall(pkg: String): String {
        val r = runProcess(arrayOf("su", "-c", "pm uninstall --user 0 $pkg"), 20_000)
        if (r.first == 0 && r.second.contains("Success")) return "uninstalled $pkg (root)\n"
        return try {
            // Hand it to Android's own uninstaller, which asks the owner on the phone.
            val confirm = PendingIntent.getActivity(
                ctx,
                0,
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ctx.packageManager.packageInstaller.uninstall(pkg, confirm.intentSender)
            "asked Android to uninstall $pkg — the phone asks the owner to confirm\n"
        } catch (e: Exception) {
            "error: ${e.message}\n"
        }
    }

    private fun rotate(arg: String): String {
        if (!Settings.System.canWrite(ctx)) {
            return "error: allow \"Modify system settings\" for this app on the phone first\n"
        }
        if (arg == "auto") {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
            return "auto-rotate on\n"
        }
        val degrees = arg.toIntOrNull() ?: return "usage: rotate 0|90|180|270|auto\n"
        val value = when (degrees) {
            0 -> 0
            90 -> 1
            180 -> 2
            270 -> 3
            else -> return "usage: rotate 0|90|180|270|auto\n"
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(ctx.contentResolver, Settings.System.USER_ROTATION, value)
        return "rotation locked at ${degrees}°\n"
    }

    private fun reboot(mode: String?): String {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(ctx.packageName)) {
            return try {
                dpm.reboot(admin())
                "rebooting (device owner)\n"
            } catch (e: Exception) {
                "error: ${e.message}\n"
            }
        }
        val target = if (mode == null || mode == "now") "reboot" else "reboot $mode"
        val r = runProcess(arrayOf("su", "-c", target), 10_000)
        if (r.first == 0) return "rebooting ($target)\n"
        return "error: reboot needs device owner or root\n"
    }

    private fun mirror(): String {
        if (MirrorService.running) return "screen mirroring is already on\n"
        Notifications.mirrorRequest(ctx)
        return "the phone now shows a \"Start screen mirroring\" notification — " +
            "Android asks the owner to allow capture each time\n"
    }

    private fun lock(): String {
        val touch = TouchService.instance
        if (touch != null && touch.globalAction("lock")) return "screen locked\n"
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isAdminActive(admin())) {
            dpm.lockNow()
            return "screen locked (administrator)\n"
        }
        return "error: needs the accessibility service or the device administrator\n"
    }

    /** A real shell, but only with this app's own uid. */
    private fun shell(command: String): Result {
        if (command.isBlank()) return usage("shell <command>")
        val r = runProcess(arrayOf("/system/bin/sh", "-c", command), 20_000)
        val err = when {
            r.second.isBlank() && r.first != 0 -> "exit ${r.first}\n"
            r.first == 124 -> ""
            else -> ""
        }
        return Result(r.first, r.second, err)
    }

    // ----------------------------------------------------------------- helpers

    fun hasRoot(): Boolean {
        if (rootChecked) return rootAvailable
        val r = runProcess(arrayOf("su", "-c", "id"), 4_000)
        rootAvailable = r.first == 0 && r.second.contains("uid=0")
        rootChecked = true
        return rootAvailable
    }

    private fun admin() = ComponentName(ctx, AdminReceiver::class.java)

    private fun runProcess(command: Array<String>, timeoutMs: Long): Pair<Int, String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            124 to (output + "\n# timed out\n")
        } else {
            process.exitValue() to output
        }
    } catch (e: Exception) {
        -1 to "${e.message ?: "command failed"}\n"
    }

    companion object {
        val HELP = """
            app shell — an app-level command line, not adbd
            ── inspect ──────────────────────────────
            help                    this text
            device                  model, android, root, owner, ports
            apps [all|user|system] [filter]
                                    packages: pkg, label, version, uid, kind, state
            ps                      processes: pid, usr|system, name
            inspect <pkg>           version, uid, sdk, apk, data dir, permissions
            ── apps and processes ───────────────────
            start <pkg>             launch an app
            kill <pkg>              kill its background processes (no root needed)
            stop <pkg>              force-stop (root)
            disable <pkg>           hide it (device owner) or pm disable-user (root)
            enable <pkg>            undo disable
            uninstall <pkg>         uninstall (root) or Android's own confirm dialog
            ── device ──────────────────────────────
            rotate 0|90|180|270|auto
                                    lock the rotation (needs "Modify system settings")
            reboot [recovery|bootloader]
                                    device owner or root
            mirror                  ask the phone to start screen mirroring
            lock                    lock the screen
            ── anything else ────────────────────────
            shell <command>         /system/bin/sh, as this app's uid

        APKs are pushed from the receiver and installed after the owner taps
        Android's install dialog. Commands that need root print an error when the
        phone has none; nothing here bypasses a consent screen.
        """.trimIndent() + "\n"
    }
}
