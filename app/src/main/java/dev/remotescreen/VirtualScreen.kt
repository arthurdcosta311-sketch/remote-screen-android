package dev.remotescreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A display that exists only for the receiver. Nothing here is drawn on the
 * phone's own screen: the frames go straight from a Canvas through a GL
 * texture into the H.264 encoder, so the receiver sees a second, independent
 * screen (its own port, its own stream).
 */
class VirtualScreen(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onInfo: (width: Int, height: Int, fps: Int) -> Unit,
    private val onFrame: (data: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit,
    private val onError: (String) -> Unit
) {

    @Volatile
    var message: String = ""

    @Volatile
    var receiverName: String = ""

    private var encoder: H264Encoder? = null
    private var renderer: EglRenderer? = null
    private var thread: Thread? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    @Volatile
    private var running = false

    private var frameIndex = 0L
    private var lastSignature = ""
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3")
        textSize = 46f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E")
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DDC97")
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3")
        textSize = 32f
    }

    fun start() {
        val enc = H264Encoder(
            width = width,
            height = height,
            bitrate = bitrate,
            fps = fps,
            onFormat = onInfo,
            onFrame = onFrame,
            onError = onError
        )
        enc.start()
        encoder = enc

        val gl = EglRenderer(enc.inputSurface, width, height)
        gl.start()
        renderer = gl

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        canvas = Canvas(bmp)

        running = true
        thread = Thread({ loop() }, "virtual-screen").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1000) } }
        thread = null
        renderer?.release()
        renderer = null
        encoder?.stop()
        encoder = null
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    private fun loop() {
        while (running) {
            val started = System.currentTimeMillis()
            val now = clock.format(Date())
            val signature = "$message|$receiverName|$now"
            if (signature != lastSignature) {
                try {
                    drawFrame(now)
                    val bmp = bitmap
                    if (bmp != null) {
                        renderer?.render(bmp, frameIndex * (1_000_000_000L / fps))
                        frameIndex++
                    }
                    lastSignature = signature
                } catch (e: Exception) {
                    onError("Second display render failed: ${e.message}")
                }
            }
            val elapsed = System.currentTimeMillis() - started
            val sleep = (1000L / fps.coerceAtLeast(1)) - elapsed
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun drawFrame(now: String) {
        val c = canvas ?: return
        val pad = 64f
        c.drawColor(Color.parseColor("#0B0F14"))

        paint.color = Color.parseColor("#3DDC97")
        c.drawRect(0f, 0f, width.toFloat(), 10f, paint)

        c.drawText("Second display", pad, 108f, titlePaint)
        c.drawText("A screen that only the receiver sees.", pad, 152f, labelPaint)

        var y = 224f
        c.drawText("RECEIVER", pad, y, labelPaint)
        c.drawText(
            if (receiverName.isBlank()) "not connected" else receiverName,
            pad + 300f,
            y,
            valuePaint
        )
        y += 46f
        c.drawText("CLOCK", pad, y, labelPaint)
        c.drawText(now, pad + 300f, y, valuePaint)
        y += 46f
        c.drawText("STREAM", pad, y, labelPaint)
        c.drawText("${width}x${height} · H.264 · ${fps} fps", pad + 300f, y, valuePaint)

        y += 54f
        paint.color = Color.parseColor("#1F2630")
        c.drawRect(pad, y, width - pad, y + 2f, paint)
        y += 52f

        c.drawText("MESSAGE FROM RECEIVER", pad, y, labelPaint)
        y += 46f

        val text = message.ifBlank {
            "Text typed on the receiver appears here. Clear it by sending an empty message."
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, (width - pad * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(false)
            .build()
        c.save()
        c.translate(pad, y)
        c.clipRect(0f, 0f, width - pad * 2, height - y - 40f)
        layout.draw(c)
        c.restore()

        paint.color = Color.parseColor("#1F2630")
        c.drawRect(pad, height - 58f, width - pad, height - 56f, paint)
        c.drawText("Rendered off-screen · port 7002", pad, height - 26f, labelPaint)
    }
}
