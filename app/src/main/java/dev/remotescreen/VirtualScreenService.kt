package dev.remotescreen

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONObject

/**
 * Serves the second display. No MediaProjection is involved: this display is
 * drawn by the app itself, so the phone screen keeps working normally and the
 * receiver gets an independent screen on port 7002.
 */
class VirtualScreenService : Service() {

    companion object {
        const val ACTION_STOP = "dev.remotescreen.action.STOP_VIRTUAL"
        const val WIDTH = 1280
        const val HEIGHT = 720

        @Volatile
        var running = false
            private set
    }

    private var server: SessionServer? = null
    private var screen: VirtualScreen? = null
    private var session: StreamSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(
            Notifications.ID_VIRTUAL,
            notification("Waiting for a paired receiver on port ${Ports.VIRTUAL}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        running = true
        startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        server = SessionServer(
            ctx = this,
            port = Ports.VIRTUAL,
            mode = "virtual",
            displayName = PairingServer.deviceName(),
            onSession = { stream -> attach(stream) },
            onSessionEnded = {
                teardown()
                stopSelf()
            },
            onStatus = { message -> notification(message) }
        ).also { it.start() }
    }

    private fun attach(stream: StreamSession) {
        session = stream
        val virtual = VirtualScreen(
            width = WIDTH,
            height = HEIGHT,
            bitrate = 2_000_000,
            fps = 15,
            onInfo = { w, h, f ->
                stream.sendInfo(
                    JSONObject()
                        .put("t", "video")
                        .put("mode", "virtual")
                        .put("codec", "avc")
                        .put("width", w)
                        .put("height", h)
                        .put("fps", f)
                        .put("touch", false)
                )
            },
            onFrame = { data, key, pts -> stream.sendFrame(data, key, pts) },
            onError = { message -> updateNotification(message) }
        )
        virtual.start()
        screen = virtual

        stream.onControl = { json -> handleControl(json) }
        stream.startReader()
        updateNotification("Second display streaming on port ${Ports.VIRTUAL}")
    }

    private fun handleControl(json: JSONObject) {
        when (json.optString("t")) {
            "text" -> screen?.message = json.optString("v")
            "name" -> screen?.receiverName = json.optString("v")
            "bye" -> session?.close()
        }
    }

    private fun notification(text: String) =
        Notifications.session(this, "Second display is on", text, stopIntent())

    private fun stopIntent() = Intent(this, VirtualScreenService::class.java).setAction(ACTION_STOP)

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_VIRTUAL, notification(text))
    }

    private fun teardown() {
        screen?.stop()
        screen = null
        server?.stop()
        server = null
        session = null
        running = false
    }

    private fun shutdown() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
