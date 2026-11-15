package dev.remotescreen

import android.content.Context
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

/**
 * Pairing listener on port 7000.
 *
 * 1. Phone sends its ephemeral P-256 public key, a salt and its name.
 * 2. Receiver answers with its public key and name.
 * 3. Both derive the same 6-digit code (ADB-style short authentication string).
 * 4. Receiver proves it by sending HMAC(master, "confirm" || typed code).
 * 5. The device owner taps "Pair" in the dialog before anything is stored.
 */
class PairingServer(
    private val ctx: Context,
    private val onRequest: (PairingRequest) -> Unit,
    private val onPaired: (PairedReceiver) -> Unit,
    private val onStatus: (String) -> Unit
) {

    class PairingRequest(val receiverName: String, val code: String) {
        private val latch = CountDownLatch(1)

        @Volatile
        private var allowed = false

        fun approve() {
            allowed = true
            latch.countDown()
        }

        fun deny() {
            allowed = false
            latch.countDown()
        }

        fun await(timeoutSeconds: Long): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS) && allowed
    }

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ acceptLoop() }, "pairing-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // ignore
        }
        server = null
        thread = null
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Ports.PAIR))
            server = ss
            onStatus("Pairing on port ${Ports.PAIR}")
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running) onStatus("Pairing listener stopped: ${e.message}")
                    break
                }
                handle(socket)
            }
        } catch (e: Exception) {
            onStatus("Cannot listen on ${Ports.PAIR}: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 180_000
        val din = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val dout = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        try {
            val keyPair = Wire.newKeyPair()
            val devicePub = keyPair.public.encoded
            val salt = ByteArray(16).also { Wire.random.nextBytes(it) }

            dout.writeInt(Handshake.MAGIC)
            dout.writeInt(Wire.PROTOCOL)
            dout.writeShort(devicePub.size)
            dout.write(devicePub)
            dout.writeShort(salt.size)
            dout.write(salt)
            dout.writeUTF(deviceName())
            dout.flush()

            val receiverPub = ByteArray(din.readUnsignedShort()).also { din.readFully(it) }
            val receiverName = din.readUTF().ifBlank { "receiver" }

            val shared = Wire.ecdh(keyPair.private, Wire.decodePublicKey(receiverPub))
            val master = Wire.hkdf(shared, salt, "RemoteScreen v1 pairing", 32)
            val code = Wire.pairingCode(master, devicePub, receiverPub)

            val typedLength = din.readUnsignedShort()
            val typed = ByteArray(typedLength).also { din.readFully(it) }
            val proof = ByteArray(din.readUnsignedShort()).also { din.readFully(it) }

            val expected = Wire.hmac(master, "confirm".toByteArray(Charsets.UTF_8), typed)
            val codeMatches = String(typed, Charsets.UTF_8).trim() == code
            if (!codeMatches || !MessageDigest.isEqual(expected, proof)) {
                dout.writeByte(0)
                dout.writeUTF("pairing code mismatch")
                dout.flush()
                onStatus("Pairing rejected: code did not match")
                return
            }

            val request = PairingRequest(receiverName, code)
            onRequest(request)
            if (!request.await(120)) {
                dout.writeByte(0)
                dout.writeUTF("denied on device")
                dout.flush()
                onStatus("Pairing with $receiverName denied")
                return
            }

            val streamKey = Wire.hkdf(shared, salt, "RemoteScreen v1 session", 32)
            val fingerprint = Wire.hex(Wire.fingerprint(receiverPub))
            val peer = PairedReceiver(fingerprint, receiverName, streamKey, System.currentTimeMillis())
            PairingStore.add(ctx, peer)

            dout.writeByte(1)
            dout.writeUTF(deviceName())
            dout.flush()
            onPaired(peer)
            onStatus("Paired with $receiverName")
        } catch (e: Exception) {
            onStatus("Pairing error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    companion object {
        fun deviceName(): String {
            val model = android.os.Build.MODEL ?: "Android"
            return if (model.isBlank()) "Android device" else model
        }
    }
}
