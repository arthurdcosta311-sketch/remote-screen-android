package dev.remotescreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional input bridge. The user enables it manually in Accessibility
 * settings; while enabled a paired receiver can tap, swipe and press
 * Back/Home. Nothing is injected without an approved session.
 */
class TouchService : AccessibilityService() {

    private val lock = Any()
    private var path: Path? = null
    private var startedAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveAt = 0L

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun touchDown(x: Int, y: Int) {
        synchronized(lock) {
            val p = Path()
            p.moveTo(x.toFloat(), y.toFloat())
            path = p
            startedAt = System.currentTimeMillis()
            lastMoveAt = startedAt
            lastX = x.toFloat()
            lastY = y.toFloat()
        }
    }

    fun touchMove(x: Int, y: Int) {
        synchronized(lock) {
            val p = path ?: return
            p.lineTo(x.toFloat(), y.toFloat())
            lastX = x.toFloat()
            lastY = y.toFloat()
            lastMoveAt = System.currentTimeMillis()
        }
    }

    fun touchUp() {
        val p: Path
        val duration: Long
        synchronized(lock) {
            p = path ?: return
            path = null
            duration = (System.currentTimeMillis() - startedAt).coerceIn(40L, 10_000L)
        }
        p.lineTo(lastX, lastY)
        dispatch(p, duration)
    }

    fun tap(x: Int, y: Int) {
        val p = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        }
        dispatch(p, 60L)
    }

    private fun dispatch(p: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(p, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    /** Best effort text entry into the focused field (search boxes, chat inputs). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        root.recycle()
        return ok
    }

    companion object {
        @Volatile
        var instance: TouchService? = null
    }
}
