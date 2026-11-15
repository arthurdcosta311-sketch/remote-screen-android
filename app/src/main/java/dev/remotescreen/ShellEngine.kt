package dev.remotescreen

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.util.Timer
import java.util.TimerTask

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
                "access" -> Result(0, Access.summary(ctx), "")
                "volume" -> Result(0, volume(pkg), "")
                "brightness" -> Result(0, brightness(pkg), "")
                "timeout" -> Result(0, screenTimeout(pkg), "")
                "battery" -> Result(0, battery(), "")
                "storage" -> Result(0, storage(), "")
                "network" -> Result(0, network(), "")
                "notify" -> Result(0, notifyPhone(args.joinToString(" ")), "")
                "open" -> Result(0, openUrl(pkg), "")
                "key" -> Result(0, key(pkg), "")
                "tap" -> Result(0, tap(args), "")
                "swipe" -> Result(0, swipe(args), "")
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
        if (r.first == 0 && !r.second.contains("Error")) return "force-stopped $pkg (root)\n"
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(ctx.packageName) && dpm.isAdminActive(admin())) {
            val failed = runCatching { dpm.setPackagesSuspended(admin(), arrayOf(pkg), true) }.getOrNull()
            if (failed != null) {
                return "suspended $pkg (device owner)\n" +
                    "# Android gives no ordinary app a force-stop; a suspended app is greyed out and cannot run.\n"
            }
        }
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

    // ------------------------------------------------ commands with no unlock

    private fun volume(arg: String?): String {
        val audio = ctx.getSystemService(AudioManager::class.java)
            ?: return "error: no audio manager\n"
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (arg?.lowercase()) {
            null, "" -> {}
            "up" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            "down" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            "mute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI
            )
            "unmute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI
            )
            else -> {
                val percent = arg?.toIntOrNull() ?: return "usage: volume up|down|mute|unmute|0-100\n"
                val index = (max * percent.coerceIn(0, 100)) / 100
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
            }
        }
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "volume   $now of $max${if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) " (muted)" else ""}\n"
    }

    private fun brightness(arg: String?): String {
        if (!Settings.System.canWrite(ctx)) return "error: allow \"Modify system settings\" first\n"
        if (arg.equals("auto", ignoreCase = true)) {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            return "brightness auto\n"
        }
        val percent = arg?.toIntOrNull() ?: return "usage: brightness 0-100|auto\n"
        Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            (percent.coerceIn(0, 100) * 255) / 100
        )
        return "brightness ${percent.coerceIn(0, 100)}%\n"
    }

    private fun screenTimeout(arg: String?): String {
        if (!Settings.System.canWrite(ctx)) return "error: allow \"Modify system settings\" first\n"
        val seconds = arg?.toIntOrNull() ?: return "usage: timeout <seconds>\n"
        val clamped = seconds.coerceIn(15, 3600)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, clamped * 1000)
        return "screen off after ${clamped}s\n"
    }

    private fun battery(): String {
        val battery = ctx.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val plugged = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "on battery"
            else -> "unknown"
        }
        val memory = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        return "battery  $percent% ($plugged)\n" +
            "memory   ${memory.availMem / 1024 / 1024} MB free of ${memory.totalMem / 1024 / 1024} MB\n"
    }

    private fun storage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return "storage  ${stat.availableBytes / 1024 / 1024} MB free of ${stat.totalBytes / 1024 / 1024} MB\n"
    }

    private fun network(): String {
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = manager?.let { m -> m.activeNetwork?.let { n -> m.getNetworkCapabilities(n) } }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return buildString {
            append("network  $transport\n")
            append("address  ${Net.localAddresses().joinToString(" ")}\n")
            append("ports    pair ${Ports.PAIR} mirror ${Ports.MIRROR} second ${Ports.VIRTUAL} shell ${Ports.SHELL}\n")
        }
    }

    private fun notifyPhone(text: String): String {
        if (text.isBlank()) return "usage: notify <text>\n"
        Notifications.result(ctx, "From the receiver", text)
        return "notification posted\n"
    }

    private fun openUrl(arg: String?): String {
        if (arg.isNullOrBlank()) return "usage: open <url>\n"
        val uri = Uri.parse(if (arg.contains("://")) arg else "https://$arg")
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened $uri\n"
        } catch (e: Exception) {
            "error: ${e.message}\n"
        }
    }

    private fun key(arg: String?): String {
        val touch = TouchService.instance ?: return "error: enable the accessibility service first\n"
        val name = arg?.lowercase() ?: return "usage: key back|home|recents|notifications|lock\n"
        return if (touch.globalAction(name)) "sent $name\n" else "error: unknown key $name\n"
    }

    private fun tap(args: List<String>): String {
        val touch = TouchService.instance ?: return "error: enable the accessibility service first\n"
        val x = args.getOrNull(0)?.toIntOrNull() ?: return "usage: tap <x> <y>\n"
        val y = args.getOrNull(1)?.toIntOrNull() ?: return "usage: tap <x> <y>\n"
        touch.tap(x, y)
        return "tapped $x,$y\n"
    }

    private fun swipe(args: List<String>): String {
        val touch = TouchService.instance ?: return "error: enable the accessibility service first\n"
        val p = args.mapNotNull { it.toIntOrNull() }
        if (p.size < 4) return "usage: swipe <x1> <y1> <x2> <y2>\n"
        touch.touchDown(p[0], p[1])
        for (step in 1..8) {
            touch.touchMove(p[0] + (p[2] - p[0]) * step / 8, p[1] + (p[3] - p[1]) * step / 8)
        }
        touch.touchUp()
        return "swiped ${p[0]},${p[1]} to ${p[2]},${p[3]}\n"
    }

    // ----------------------------------------------------------------- helpers

    fun hasRoot(): Boolean {
        if (rootChecked) return rootAvailable
        val r = runProcess(arrayOf("su", "-c", "id"), 8_000)
        rootAvailable = r.first == 0 && r.second.contains("uid=0")
        rootChecked = true
        return rootAvailable
    }

    private fun admin() = ComponentName(ctx, AdminReceiver::class.java)

    /**
     * Runs a command and returns (exit code, combined output). A watchdog kills it at
     * [timeoutMs] so a waiting `su` prompt or a stuck child can never block the shell.
     */
    private fun runProcess(command: Array<String>, timeoutMs: Long): Pair<Int, String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val watchdog = Timer("shell-watchdog", true)
        watchdog.schedule(object : TimerTask() {
            override fun run() {
                if (process.isAlive) process.destroyForcibly()
            }
        }, timeoutMs)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        watchdog.cancel()
        if (exit == 137) 124 to (output + "\n# timed out\n") else exit to output
    } catch (e: Exception) {
        -1 to "${e.message ?: "command failed"}\n"
    }

    companion object {
        val HELP = """
            app shell — an app-level command line, not adbd
            ── inspect ──────────────────────────────
            help                    this text
            device                  model, android, root, owner, ports
            access                  what this install may do, and how to unlock more
            apps [all|user|system] [filter]
                                    packages: pkg, label, version, uid, kind, state
            ps                      processes: pid, usr|system, name
            inspect <pkg>           version, uid, sdk, apk, data dir, permissions
            battery                 charge, charging state, memory
            storage                 free space on /data
            network                 transport, addresses, ports
            ── apps and processes ───────────────────
            start <pkg>             launch an app
            kill <pkg>              kill its background processes (no root needed)
            stop <pkg>              force-stop (root) or suspend (device owner)
            disable <pkg>           hide it (device owner) or pm disable-user (root)
            enable <pkg>            undo disable
            uninstall <pkg>         uninstall (root) or Android's own confirm dialog
            ── device ──────────────────────────────
            rotate 0|90|180|270|auto
                                    lock the rotation (needs "Modify system settings")
            brightness 0-100|auto   screen brightness (same permission)
            timeout <seconds>       screen-off delay (same permission)
            volume up|down|mute|unmute|0-100
                                    media volume
            key back|home|recents|notifications|lock
                                    phone-wide key (accessibility service)
            tap <x> <y>             tap at a pixel (accessibility service)
            swipe <x1> <y1> <x2> <y2>
                                    swipe between pixels (accessibility service)
            open <url>              open a link on the phone
            notify <text>           post a notification on the phone
            reboot [recovery|bootloader]
                                    root or device owner
            mirror                  ask the phone to start screen mirroring
            lock                    lock the screen
            ── anything else ────────────────────────
            shell <command>         /system/bin/sh, as this app's uid

        APKs are pushed from the receiver and installed after the owner taps
        Android's install dialog. Wireless-debugging pairing is not reachable
        from inside an app (Android pairs hosts over TLS with SPAKE2 in its own
        BoringSSL), so run `access` to see what root or a device-owner install
        would add. Nothing here bypasses a consent screen.
        """.trimIndent() + "\n"
    }
}
