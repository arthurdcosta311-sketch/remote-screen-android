package dev.remotescreen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/**
 * Surface-input H.264 encoder. Both pipelines (mirror and virtual screen)
 * render into [inputSurface]; the drain thread hands Annex-B access units to
 * [onFrame] with SPS/PPS prefixed to every key frame so a receiver can start
 * decoding on any IDR.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onFormat: (width: Int, height: Int, fps: Int) -> Unit,
    private val onFrame: (data: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit,
    private val onError: (String) -> Unit
) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var csd: ByteArray? = null

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        thread = Thread({ drain() }, "h264-encoder").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (e: Exception) {
            // encoder may be shutting down
        }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(800) } }
        thread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun drain() {
        while (running) {
            try {
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        csd = readBuffer(format, "csd-0") + readBuffer(format, "csd-1")
                        onFormat(width, height, fps)
                        requestKeyFrame()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (index >= 0) {
                            try {
                                val isConfig =
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (bufferInfo.size > 0 && !isConfig) {
                                    val buffer = codec.getOutputBuffer(index)
                                    if (buffer != null) {
                                        buffer.position(bufferInfo.offset)
                                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                                        val data = ByteArray(bufferInfo.size)
                                        buffer.get(data)
                                        val key =
                                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                        val payload = if (key) (csd ?: ByteArray(0)) + data else data
                                        onFrame(payload, key, bufferInfo.presentationTimeUs)
                                    }
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) onError("Encoder failed: ${e.message}")
                running = false
            }
        }
    }

    private fun readBuffer(format: MediaFormat, name: String): ByteArray {
        val buffer = try {
            format.getByteBuffer(name)
        } catch (e: Exception) {
            null
        } ?: return ByteArray(0)
        buffer.rewind()
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
