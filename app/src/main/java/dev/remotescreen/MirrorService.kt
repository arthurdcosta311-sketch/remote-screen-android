package dev.remotescreen

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import org.json.JSONObject

/** Foreground service that mirrors this device's display to a paired receiver. */
class MirrorService : Service() {

    companion object {
        const val ACTION_STOP = "dev.remotescreen.action.STOP_MIRROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var running = false
            private set
    }

    private var server: SessionServer? = null
    private var pipeline: MirrorPipeline? = null
    private var projection: MediaProjection? = null
    private var session: StreamSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(
            Notifications.ID_MIRROR,
            notification("Waiting for a paired receiver on port ${Ports.MIRROR}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        running = true

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            shutdown()
            return START_NOT_STICKY
        }

        if (projection == null) {
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, resultData)
        }
        if (projection == null) {
            shutdown()
            return START_NOT_STICKY
        }

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
            port = Ports.MIRROR,
            mode = "mirror",
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
        val activeProjection = projection ?: return
        session = stream

        val metrics = resources.displayMetrics
        val (width, height) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        val fps = 30

        val mirror = MirrorPipeline(
            ctx = this,
            projection = activeProjection,
            width = width,
            height = height,
            bitrate = 3_000_000,
            fps = fps,
            onInfo = { w, h, f ->
                stream.sendInfo(
                    JSONObject()
                        .put("t", "video")
                        .put("mode", "mirror")
                        .put("codec", "avc")
                        .put("width", w)
                        .put("height", h)
                        .put("fps", f)
                        .put("touch", TouchService.instance != null)
                )
            },
            onFrame = { data, key, pts -> stream.sendFrame(data, key, pts) },
            onStopped = {
                teardown()
                stopSelf()
            }
        )
        mirror.start()
        pipeline = mirror

        stream.onControl = { json -> handleControl(json) }
        stream.startReader()
        updateNotification("Streaming ${width}x${height} to a paired receiver")
    }

    private fun handleControl(json: JSONObject) {
        when (json.optString("t")) {
            "touch" -> {
                val touchService = TouchService.instance ?: return
                val metrics = resources.displayMetrics
                val x = (json.optDouble("x", 0.0) * metrics.widthPixels)
                    .toInt().coerceIn(0, metrics.widthPixels - 1)
                val y = (json.optDouble("y", 0.0) * metrics.heightPixels)
                    .toInt().coerceIn(0, metrics.heightPixels - 1)
                when (json.optString("a")) {
                    "down" -> touchService.touchDown(x, y)
                    "move" -> touchService.touchMove(x, y)
                    "up" -> touchService.touchUp()
                    "tap" -> touchService.tap(x, y)
                }
            }
            "key" -> TouchService.instance?.globalAction(json.optString("k"))
            "text" -> TouchService.instance?.typeText(json.optString("v"))
            "bye" -> session?.close()
        }
    }

    private fun scaledSize(widthPixels: Int, heightPixels: Int): Pair<Int, Int> {
        val longest = maxOf(widthPixels, heightPixels)
        val scale = minOf(1.0, 1280.0 / longest)
        val rounded = { value: Int -> ((value * scale).toInt() / 16) * 16 }
        return rounded(widthPixels).coerceAtLeast(16) to rounded(heightPixels).coerceAtLeast(16)
    }

    private fun notification(text: String) =
        Notifications.session(this, "Screen sharing is on", text, stopIntent())

    private fun stopIntent() = Intent(this, MirrorService::class.java).setAction(ACTION_STOP)

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(Notifications.ID_MIRROR, notification(text))
    }

    private fun teardown() {
        pipeline?.stop()
        pipeline = null
        runCatching { projection?.stop() }
        projection = null
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
