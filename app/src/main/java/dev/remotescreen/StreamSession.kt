package dev.remotescreen

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One authenticated receiver connection. Writes are serialised because the
 * encoder thread and control replies share the same socket.
 */
class StreamSession(
    private val channel: SecureChannel,
    private val onClosed: () -> Unit
) {

    @Volatile
    var onControl: (JSONObject) -> Unit = {}

    private val closed = AtomicBoolean(false)
    private var reader: Thread? = null

    val isClosed: Boolean get() = closed.get()

    fun startReader() {
        reader = Thread({
            try {
                while (!closed.get()) {
                    val msg = channel.receive() ?: break
                    when (msg.type) {
                        Msg.CONTROL -> try {
                            onControl(JSONObject(String(msg.payload, Charsets.UTF_8)))
                        } catch (e: Exception) {
                            // ignore malformed control messages
                        }
                        Msg.BYE -> break
                    }
                }
            } catch (e: Exception) {
                // socket closed or auth failure: fall through
            }
            close()
        }, "session-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun sendInfo(info: JSONObject) {
        try {
            channel.send(Msg.VIDEO_INFO, info.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            close()
        }
    }

    /** Any receiver-bound control message (shell replies, status). */
    fun sendControl(json: JSONObject) {
        try {
            channel.send(Msg.CONTROL, json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            close()
        }
    }

    fun sendFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        if (closed.get()) return
        val payload = ByteArray(9 + data.size)
        payload[0] = if (keyFrame) 1 else 0
        ByteBuffer.wrap(payload, 1, 8).putLong(ptsUs)
        System.arraycopy(data, 0, payload, 9, data.size)
        try {
            channel.send(Msg.VIDEO_FRAME, payload)
        } catch (e: Exception) {
            close()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) onClosed()
    }
}
