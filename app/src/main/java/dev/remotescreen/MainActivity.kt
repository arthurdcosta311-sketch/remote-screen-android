package dev.remotescreen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var peersView: LinearLayout
    private lateinit var adminButton: Button
    private lateinit var touchButton: Button
    private lateinit var unattendedSwitch: Switch
    private var pairing: PairingServer? = null
    private var pairingStatus: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        setContentView(buildUi())
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        startPairing()
        refresh()
    }

    override fun onPause() {
        stopPairing()
        super.onPause()
    }

    override fun onDestroy() {
        stopPairing()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pairing

    private fun startPairing() {
        if (pairing != null) return
        val server = PairingServer(
            ctx = this,
            onRequest = { request -> runOnUiThread { showPairingDialog(request) } },
            onPaired = { peer ->
                runOnUiThread {
                    toast("Paired with ${peer.name}")
                    refresh()
                }
            },
            onStatus = { message ->
                runOnUiThread {
                    pairingStatus = message
                    refresh()
                }
            }
        )
        server.start()
        pairing = server
    }

    private fun stopPairing() {
        pairing?.stop()
        pairing = null
    }

    private fun showPairingDialog(request: PairingServer.PairingRequest) {
        if (isFinishing || isDestroyed) {
            request.deny()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with ${request.receiverName}?")
            .setMessage(
                "Pairing code:  ${request.code}\n\n" +
                    "The receiver typed this code. Only pair if the same six digits are on the " +
                    "receiver's screen right now."
            )
            .setPositiveButton("Pair") { _, _ -> request.approve() }
            .setNegativeButton("Deny") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    // ---------------------------------------------------------------- sessions

    private fun askProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            startForegroundService(intent)
            toast("Screen sharing is starting")
        } else {
            toast("Screen capture was not allowed")
        }
        refresh()
    }

    private fun startVirtual() {
        startForegroundService(Intent(this, VirtualScreenService::class.java))
        toast("Second display is starting")
        refresh()
    }

    private fun stopAll() {
        stopService(Intent(this, MirrorService::class.java))
        stopService(Intent(this, VirtualScreenService::class.java))
        toast("Sessions stopped")
        refresh()
    }

    // ------------------------------------------------------------------ admin

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun isAdmin(): Boolean =
        getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent())

    private fun toggleAdmin() {
        val manager = getSystemService(DevicePolicyManager::class.java)
        if (manager.isAdminActive(adminComponent())) {
            manager.removeActiveAdmin(adminComponent())
            toast("Device administrator disabled")
        } else {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets this app lock the screen on request. Remote sessions still need " +
                            "your approval and always show a notification."
                    )
            )
        }
    }

    private fun lockNow() {
        val manager = getSystemService(DevicePolicyManager::class.java)
        if (manager.isAdminActive(adminComponent())) {
            manager.lockNow()
        } else {
            toast("Enable device administrator first")
        }
    }

    // ------------------------------------------------------------------ adb

    private fun openAdb() {
        val manager = getSystemService(DevicePolicyManager::class.java)
        val summary = StringBuilder()
        if (manager.isDeviceOwnerApp(packageName)) {
            val usb = runCatching {
                manager.setGlobalSetting(adminComponent(), "adb_enabled", "1")
            }.isSuccess
            val wifi = runCatching {
                manager.setGlobalSetting(adminComponent(), "adb_wifi_enabled", "1")
            }.isSuccess
            summary.append("Device owner detected. USB debugging: ")
                .append(if (usb) "on" else "refused")
                .append(", wireless debugging: ")
                .append(if (wifi) "on" else "refused")
                .append(".\n\n")
        } else {
            summary.append(
                "Only a device-owner install may flip these switches, so enable them by hand: " +
                    "Developer options → Wireless debugging.\n\n"
            )
        }
        summary.append(
            "Android's own pairing shows its code in that dialog; on the receiver run " +
                "adb pair <ip>:<port>. This app's pairing on port ${Ports.PAIR} is a separate " +
                "protocol; it does not proxy adb, because adb needs the adbd daemon and its " +
                "host key exchange."
        )
        AlertDialog.Builder(this)
            .setTitle("ADB / wireless debugging")
            .setMessage(summary.toString())
            .setPositiveButton("Open developer options") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // -------------------------------------------------------------- rendering

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(36))
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        root.addView(TextView(this).apply {
            text = "Remote Screen"
            textSize = 27f
            setTextColor(Color.parseColor("#E6EDF3"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(body("Pairing-code remote access. Every session keeps a notification on screen; nothing runs hidden."))

        statusView = body("").apply {
            setTextColor(Color.parseColor("#3DDC97"))
            typeface = Typeface.MONOSPACE
        }
        root.addView(statusView)

        root.addView(section("HOW TO CONNECT"))
        root.addView(body("1. Open your receiver and enter the phone address and the pairing code below.\n2. Approve the dialog that appears here.\n3. Start mirroring or the second display."))

        root.addView(section("PAIRED RECEIVERS"))
        peersView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersView)

        root.addView(section("SHARE THIS SCREEN"))
        root.addView(body("Uses Android's own screen-capture consent screen each time you start it."))
        root.addView(action("Start screen mirroring") { askProjection() })

        root.addView(section("SECOND DISPLAY"))
        root.addView(body("A display that is rendered here and sent to the receiver only. Your phone screen stays free, so it keeps working normally while the receiver watches the second screen on port ${Ports.VIRTUAL}."))
        root.addView(action("Start second screen") { startVirtual() })
        root.addView(action("Stop all sessions") { stopAll() })

        root.addView(section("INPUT"))
        root.addView(body("Touch, Back/Home and text injection are optional and need the accessibility service. Without it the receiver is view-only."))
        touchButton = action("Open accessibility settings") { openAccessibility() }
        root.addView(touchButton)

        root.addView(section("DEVICE ADMINISTRATOR"))
        root.addView(body("Optional. Gives the app the standard admin powers (lock now). It does not block uninstall and cannot hide itself."))
        adminButton = action("Enable device administrator") { toggleAdmin() }
        root.addView(adminButton)
        root.addView(action("Lock screen now") { lockNow() })

        root.addView(section("UNATTENDED ACCESS"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        unattendedSwitch = Switch(this).apply {
            isChecked = PairingStore.unattended(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                PairingStore.setUnattended(this@MainActivity, checked)
                toast(if (checked) "Unattended mode on" else "Approval required for every session")
                refresh()
            }
        }
        row.addView(unattendedSwitch)
        row.addView(TextView(this).apply {
            text = "  Accept paired receivers without a tap"
            textSize = 15f
            setTextColor(Color.parseColor("#E6EDF3"))
        })
        root.addView(row)
        root.addView(body("Off by default. When off, every session waits for your Allow/Deny notification. Turning it on still needs a receiver that already paired here, and the session notification stays visible."))

        root.addView(section("ADB / WIRELESS DEBUGGING"))
        root.addView(body("Android's wireless debugging is separate from this app; a device-owner install can switch it on for you."))
        root.addView(action("ADB settings") { openAdb() })

        root.addView(body("\nReceivers are stored encrypted with a key that never leaves this device's Keystore.", "#5B6673"))

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        val addresses = Net.localAddresses()
        val text = buildString {
            append(if (addresses.isEmpty()) "No network yet — join Wi-Fi or start a hotspot." else addresses.joinToString(" · "))
            append("\n\nPairing port ${Ports.PAIR}")
            append("\nMirror port ${Ports.MIRROR}")
            append("\nSecond display port ${Ports.VIRTUAL}")
            append("\n\nMirroring: ${if (MirrorService.running) "running" else "off"}")
            append("\nSecond display: ${if (VirtualScreenService.running) "running" else "off"}")
            append("\nInput bridge: ${if (TouchService.instance != null) "on" else "off"}")
            append("\nPairing listener: ${if (pairing != null) "on" else "off"}")
            if (pairingStatus.isNotBlank()) append("\n\n$pairingStatus")
        }
        statusView.text = text
        adminButton.text =
            if (isAdmin()) "Disable device administrator" else "Enable device administrator"
        touchButton.text =
            if (TouchService.instance != null) "Accessibility input: on" else "Open accessibility settings"
        unattendedSwitch.isChecked = PairingStore.unattended(this)
        renderPeers()
    }

    private fun renderPeers() {
        peersView.removeAllViews()
        val peers = PairingStore.peers(this)
        if (peers.isEmpty()) {
            peersView.addView(body("Nobody paired yet."))
            return
        }
        for (peer in peers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = peer.name
                textSize = 16f
                setTextColor(Color.parseColor("#E6EDF3"))
            })
            row.addView(body("key ${peer.fingerprint.take(16)}…", "#5B6673", 12f))
            row.addView(action("Forget") {
                PairingStore.remove(this, peer.fingerprint)
                refresh()
            })
            peersView.addView(row)
        }
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.12f
        setTextColor(Color.parseColor("#3DDC97"))
        setPadding(0, dp(24), 0, dp(4))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun body(text: String, color: String = "#8B949E", size: Float = 14f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(2))
        }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { topMargin = dp(8) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Notifications are required so sessions can never run silently")
        }
    }

    private companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_NOTIFICATIONS = 1002
    }
}
