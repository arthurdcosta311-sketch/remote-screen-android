package dev.remotescreen

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's own wireless debugging: its settings screen, and the ports adbd is
 * listening on.
 *
 * This app still cannot pair with its own adbd — the pairing is TLS with a SPAKE2
 * key exchange that lives only in Android's BoringSSL — but the *receiver* can,
 * and it links a port of AOSP's adb that carries both. What it needs from the
 * phone is a way to reach the screen and to know the ports, and that is exactly
 * what this does, so the only thing a person types is the six digits Android
 * shows in its own dialog.
 */
object Wireless {

    /** adbd picks its wireless-debugging ports from this range. */
    const val FIRST_PORT = 30000
    const val LAST_PORT = 49999

    /** Android's wireless-debugging switch, when the OS lets an app read it. */
    fun enabled(ctx: Context): Boolean? = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    /**
     * Opens Settings on the wireless-debugging page, falling back to developer
     * options and then to Settings itself. Returns what happened, for the receiver
     * to print.
     */
    fun openSettings(ctx: Context): String {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.development.WirelessDebuggingActivity"
                )
            ) to "Settings → Wireless debugging",
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) to "Settings → Developer options",
            Intent(Settings.ACTION_SETTINGS) to "Settings"
        )
        for ((intent, label) in candidates) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "opened $label on the phone\n" +
                    "# Switch Wireless debugging on, then tap \"Pair device with pairing code\".\n"
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            } catch (e: RuntimeException) {
                continue
            }
        }
        // Android 10+ refuses activity starts from the background, and the shell
        // runs in a service. A notification is how the owner is asked instead.
        Notifications.wirelessRequest(ctx)
        return "could not open Settings from the background — the phone now shows a notification; " +
            "tap it to open Wireless debugging\n"
    }

    /**
     * Ports on 127.0.0.1 that accept a connection. adbd listens on every
     * interface, so its wireless-debugging ports show up here; a refused port
     * answers at once on loopback, so the whole range is cheap to walk.
     */
    fun openPorts(deadlineMs: Long = 9_000): List<Int> {
        val found = Collections.synchronizedList(mutableListOf<Int>())
        val loopback = InetAddress.getLoopbackAddress()
        val stopAt = System.currentTimeMillis() + deadlineMs
        val pool = Executors.newFixedThreadPool(48) { runnable ->
            Thread(runnable, "adb-port-scan").apply { isDaemon = true }
        }
        for (port in FIRST_PORT..LAST_PORT) {
            pool.execute {
                if (System.currentTimeMillis() > stopAt) return@execute
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(loopback, port), 150)
                        found.add(port)
                    }
                } catch (e: Exception) {
                    // refused, closed or timed out: nothing is listening there
                }
            }
        }
        pool.shutdown()
        runCatching { pool.awaitTermination(deadlineMs + 3_000, TimeUnit.MILLISECONDS) }
        return found.sorted()
    }

    /**
     * What the receiver needs to know: whether the switch is on, which ports are
     * open, and how to tell the pairing port from the connect port.
     */
    fun report(ctx: Context): String {
        val wifi = when (enabled(ctx)) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
        val ports = openPorts()
        return buildString {
            append("wifi     $wifi\n")
            append("ports    ${if (ports.isEmpty()) "-" else ports.joinToString(" ")}\n")
            when {
                ports.isEmpty() -> append(
                    "# nothing is listening: switch Wireless debugging on in Settings → Developer options\n"
                )
                ports.size == 1 -> append(
                    "# one port is open: that is the connect port. The pairing port exists only while " +
                        "\"Pair device with pairing code\" is showing, so open it and scan again.\n"
                )
                else -> append(
                    "# ${ports.size} ports are open: the pairing dialog is showing, so the port that " +
                        "was not open before is the pairing port.\n"
                )
            }
        }
    }
}
