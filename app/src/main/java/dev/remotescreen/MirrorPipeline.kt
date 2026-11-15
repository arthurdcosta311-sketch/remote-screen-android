package dev.remotescreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/** Captures the default display through MediaProjection and feeds the encoder surface. */
class MirrorPipeline(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onInfo: (width: Int, height: Int, fps: Int) -> Unit,
    private val onFrame: (data: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit,
    private val onStopped: () -> Unit
) {

    private var encoder: H264Encoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        // Required before createVirtualDisplay on Android 14+.
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                    onStopped()
                }
            },
            handler
        )

        val enc = H264Encoder(
            width = width,
            height = height,
            bitrate = bitrate,
            fps = fps,
            onFormat = { w, h, f -> onInfo(w, h, f) },
            onFrame = onFrame,
            onError = { }
        )
        enc.start()
        encoder = enc

        val density = ctx.resources.displayMetrics.densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "remote-screen",
            width,
            height,
            density.coerceAtLeast(120),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface,
            null,
            handler
        )
    }

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        encoder?.stop()
        encoder = null
    }
}
