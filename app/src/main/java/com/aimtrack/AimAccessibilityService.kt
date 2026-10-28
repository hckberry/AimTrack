package com.aimtrack

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class AimAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AimAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        State.gameFocused = false
        return super.onUnbind(intent)
    }

    /**
     * Foreground gating. The game counts as focused until a different app's window comes up.
     * Our own windows, system UI and the active keyboard are ignored so they don't cause
     * false pauses.
     */
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == imePackage()) return
        val focused = pkg == State.settings.targetPackage
        if (focused && !State.gameFocused) State.focusedSince = SystemClock.uptimeMillis()
        State.gameFocused = focused
    }

    private fun imePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    /**
     * Dispatches one swipe of vector (vx, vy) screen px. [scale] converts reference px
     * (speed, margins) to screen px. [done] runs on completion or cancel.
     */
    fun swipe(vx: Float, vy: Float, s: AimSettings, scale: Float, done: () -> Unit) {
        val len = hypot(vx, vy)
        if (len < 1f) { done(); return }

        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val margin = 8f * scale
        val x0 = fit(s.anchorX * w, vx, w, margin)
        val y0 = fit(s.anchorY * h, vy, h, margin)

        val path = Path().apply { moveTo(x0, y0); lineTo(x0 + vx, y0 + vy) }
        val durationMs = maxOf(s.holdMs, (len / (s.speedPxPerSec * scale) * 1000f).toLong(), 30L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done() }
            override fun onCancelled(g: GestureDescription?) { done() }
        }, null)
        if (!accepted) done()
    }

    /** Shifts the start point so the whole stroke stays on screen without changing its vector. */
    private fun fit(anchor: Float, v: Float, size: Float, margin: Float): Float {
        val lo = margin + maxOf(0f, -v)
        val hi = size - margin - maxOf(0f, v)
        return if (lo <= hi) anchor.coerceIn(lo, hi) else size / 2f
    }
}
