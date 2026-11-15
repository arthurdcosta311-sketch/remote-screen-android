package dev.remotescreen

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated listener for a streaming port. The receiver proves it holds a
 * pairing key; if unattended access is off, the device owner also has to allow
 * the connection from the approval notification.
 */
class SessionServer(
    private val ctx: Context,
    private val port: Int,
    private val mode: String,
    private val displayName: String,
    private val onSession: (StreamSession) -> Unit,
    private val onSessionEnded: () -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val actionApprove = "dev.remotescreen.approve.$port"
    private val actionDeny = "dev.remotescreen.deny.$port"

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var activeSocket: Socket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ acceptLoop() }, "session-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { activeSocket?.close() }
        runCatching { server?.close() }
        activeSocket = null
        server = null
        thread = null
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            onStatus("Listening on $port")
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running) onStatus("Listener on $port stopped: ${e.message}")
                    break
                }
                handle(socket)
            }
        } catch (e: Exception) {
            onStatus("Cannot listen on $port: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        activeSocket = socket
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
        val din = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        val dout = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        try {
            val serverNonce = ByteArray(32).also { Wire.random.nextBytes(it) }
            dout.writeInt(Handshake.MAGIC)
            dout.writeInt(Wire.PROTOCOL)
            dout.write(serverNonce)
            dout.writeUTF(displayName)
            dout.flush()

            val fingerprint = ByteArray(32).also { din.readFully(it) }
            val clientNonce = ByteArray(32).also { din.readFully(it) }
            val proof = ByteArray(32).also { din.readFully(it) }

            val peer = PairingStore.find(ctx, Wire.hex(fingerprint))
            if (peer == null) {
                reject(dout, "receiver is not paired")
                onStatus("Rejected an unpaired receiver")
                return
            }

            val expected = Wire.hmac(peer.streamKey, serverNonce, clientNonce)
            if (!MessageDigest.isEqual(expected, proof)) {
                reject(dout, "authentication failed")
                onStatus("Rejected ${peer.name}: bad authentication")
                return
            }

            if (!PairingStore.unattended(ctx) && !requestApproval(peer.name)) {
                reject(dout, "denied on device")
                onStatus("Denied ${peer.name}")
                return
            }

            dout.writeByte(1)
            dout.flush()
            socket.soTimeout = 0

            val streamKey = Wire.hkdf(
                peer.streamKey,
                serverNonce + clientNonce,
                "RemoteScreen v1 stream",
                32
            )
            val channel = SecureChannel(din, dout, streamKey)
            val session = StreamSession(channel) {
                runCatching { socket.close() }
                onSessionEnded()
            }
            onSession(session)
        } catch (e: Exception) {
            onStatus("Session on $port ended: ${e.message}")
            runCatching { socket.close() }
            onSessionEnded()
        } finally {
            activeSocket = null
        }
    }

    private fun reject(dout: DataOutputStream, reason: String) {
        runCatching {
            dout.writeByte(0)
            dout.writeUTF(reason)
            dout.flush()
        }
    }

    private fun requestApproval(receiverName: String): Boolean {
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                allowed.set(intent?.action == actionApprove)
                latch.countDown()
            }
        }
        ctx.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(actionApprove)
                addAction(actionDeny)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        val manager = ctx.getSystemService(NotificationManager::class.java)
        return try {
            val what = if (mode == "mirror") "mirror your screen" else "open the second display"
            manager.notify(
                Notifications.ID_APPROVAL,
                Notifications.approval(
                    ctx,
                    "Allow $receiverName?",
                    "$receiverName wants to $what. Allow only if you expected this connection.",
                    Intent(actionApprove).setPackage(ctx.packageName),
                    Intent(actionDeny).setPackage(ctx.packageName)
                )
            )
            latch.await(60, TimeUnit.SECONDS) && allowed.get()
        } catch (e: Exception) {
            false
        } finally {
            manager.cancel(Notifications.ID_APPROVAL)
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
}
