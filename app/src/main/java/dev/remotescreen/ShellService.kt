package dev.remotescreen

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Remote shell on port 7003: the app-level command line plus APK upload.
 *
 * Same handshake, approval and encryption as the video sessions; it only runs
 * while the owner starts it here, and it keeps a notification on screen like
 * every other session.
 */
class ShellService : Service() {

    companion object {
        const val ACTION_STOP = "dev.remotescreen.action.STOP_SHELL"
        private const val MAX_UPLOAD = 512L * 1024 * 1024

        @Volatile
        var running = false
            private set
    }

    private class Upload(val id: Int, val name: String, val size: Long) {
        val data = ByteArrayOutputStream()
    }

    private var server: SessionServer? = null
    private var session: StreamSession? = null
    private var engine: ShellEngine? = null
    private var upload: Upload? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shell-commands") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(
            Notifications.ID_SHELL,
            notification("Waiting for a paired receiver on port ${Ports.SHELL}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        running = true
        if (engine == null) engine = ShellEngine(this)
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
            port = Ports.SHELL,
            mode = "shell",
            displayName = PairingServer.deviceName(),
            onSession = { stream -> attach(stream) },
            onSessionEnded = { updateNotification("Waiting for a paired receiver on port ${Ports.SHELL}") },
            onStatus = { message -> updateNotification(message) }
        ).also { it.start() }
    }

    private fun attach(stream: StreamSession) {
        session = stream
        stream.onControl = { json -> handleControl(json) }
        stream.startReader()
        updateNotification("Remote shell connected on port ${Ports.SHELL}")
    }

    private fun handleControl(json: JSONObject) {
        when (json.optString("t")) {
            "exec" -> {
                val id = json.optInt("id", 0)
                val command = json.optString("cmd")
                worker.execute {
                    val result = engine?.run(command) ?: ShellEngine.Result(1, "", "shell not ready\n")
                    reply(
                        JSONObject()
                            .put("t", "shell")
                            .put("id", id)
                            .put("code", result.code)
                            .put("out", result.out)
                            .put("err", result.err)
                    )
                }
            }
            "apk" -> {
                val id = json.optInt("id", 0)
                val fresh = Upload(id, json.optString("name", "upload.apk"), json.optLong("size", 0))
                upload = fresh
                reply(JSONObject().put("t", "apk-status").put("id", id).put("state", "receiving").put("message", ""))
            }
            "apk-chunk" -> {
                val current = upload ?: return
                val chunk = runCatching { Base64.decode(json.optString("data"), Base64.DEFAULT) }.getOrNull()
                if (chunk == null) {
                    upload = null
                    reply(notifyError(current.id, "bad chunk"))
                    return
                }
                if (current.size > MAX_UPLOAD || current.data.size().toLong() + chunk.size > MAX_UPLOAD) {
                    upload = null
                    reply(notifyError(current.id, "upload larger than 512 MB"))
                    return
                }
                current.data.write(chunk)
            }
            "apk-end" -> {
                val current = upload ?: return
                upload = null
                worker.execute { finishUpload(current) }
            }
            "bye" -> session?.close()
        }
    }

    private fun notifyError(id: Int, message: String) = JSONObject()
        .put("t", "apk-status")
        .put("id", id)
        .put("state", "error")
        .put("message", message)

    private fun finishUpload(current: Upload) {
        val bytes = current.data.toByteArray()
        if (current.size > 0 && bytes.size.toLong() != current.size) {
            reply(notifyError(current.id, "size mismatch: ${bytes.size} of ${current.size}"))
            return
        }
        val file = try {
            val dir = File(cacheDir, "uploads").apply { mkdirs() }
            File(dir, safeName(current.name)).also { it.writeBytes(bytes) }
        } catch (e: Exception) {
            reply(notifyError(current.id, "cannot store the apk: ${e.message}"))
            return
        }
        reply(
            JSONObject()
                .put("t", "apk-status")
                .put("id", current.id)
                .put("state", "received")
                .put("message", file.absolutePath)
        )
        Installer.request(this, file)
    }

    private fun safeName(name: String): String {
        val cleaned = name.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return cleaned.takeIf { it.endsWith(".apk") } ?: "upload.apk"
    }

    private fun reply(json: JSONObject) {
        session?.sendControl(json)
    }

    private fun notification(text: String) =
        Notifications.session(this, "Remote shell is on", text, stopIntent())

    private fun stopIntent() = Intent(this, ShellService::class.java).setAction(ACTION_STOP)

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.ID_SHELL, notification(text))
    }

    private fun teardown() {
        worker.shutdownNow()
        server?.stop()
        server = null
        session = null
        upload = null
        running = false
    }

    private fun shutdown() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
