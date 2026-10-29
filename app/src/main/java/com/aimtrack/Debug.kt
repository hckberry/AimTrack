package com.aimtrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/** Snapshot published by the detect thread for the overlay. Offsets are in reference px. */
data class DebugInfo(
    val centerRgb: Int,
    val blobs: Int,
    val target: TargetOffset?,   // tracked target, or the closest candidate while stabilizing
    val aiming: Boolean,         // true once the tracker accepts the target
    val tx: Float, val ty: Float, // target position, screen px
    val offX: Float, val offY: Float,
    val fps: Float, val detMs: Float
)

/**
 * Full-screen, non-touchable overlay: detection region rectangle, target marker + offset line,
 * a log panel, and a dot that flashes when a swipe is dispatched.
 *
 * Overlays are visible to MediaProjection, so drawings use the color opposite your target's hue
 * and the rectangle sits just outside the detection region. The white log text can still
 * interfere with a white target; switch debug off for normal play.
 */
class DebugOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var view: DebugView? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val on = State.debugOn
            v.visibility = if (on) View.VISIBLE else View.GONE
            if (on) v.invalidate()
            ui.postDelayed(this, 66)
        }
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val v = DebugView(ctx).apply { visibility = View.GONE }
        wm.addView(v, lp)
        view = v
        ui.post(tick)
    }

    fun hide() {
        ui.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}

private class DebugView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.MONOSPACE; textSize = 11f * d
    }
    private val bg = Paint().apply { color = 0xAA000000.toInt() }

    override fun onDraw(c: Canvas) {
        val s = State.settings
        val w = width.toFloat()
        val h = height.toFloat()
        val ref = minOf(w, h) / 1080f
        val col = debugColor(s.targetColor)
        val info = State.debug

        // detection region (same geometry as TargetDetector), drawn just outside its edge
        val rh = h * s.regionFraction
        val rw = minOf(w, rh * 1.6f)
        val pad = 6f * ref
        line.color = col
        line.strokeWidth = max(2f, 3f * ref)
        c.drawRect(w / 2 - rw / 2 - pad, h / 2 - rh / 2 - pad, w / 2 + rw / 2 + pad, h / 2 + rh / 2 + pad, line)

        // target marker and offset line
        if (info?.target != null) {
            c.drawLine(w / 2, h / 2, info.tx, info.ty, line)
            c.drawCircle(info.tx, info.ty, 14f * ref + 4f, line)
        }

        // dispatch flash (top-center, outside the detection region)
        val now = SystemClock.uptimeMillis()
        if (State.lastDispatchMs != 0L && now - State.lastDispatchMs < 150) {
            fill.color = 0xFF00FF66.toInt()
            c.drawCircle(w / 2, 56f * ref, 26f * ref, fill)
        }

        // log panel
        val state = when {
            !State.running -> "STOPPED"
            State.paused -> "PAUSED"
            !State.gameFocused -> "GAME NOT FOCUSED"
            State.testMode -> "TEST MODE"
            else -> "ACTIVE"
        }
        val lines = ArrayList<String>()
        lines += "Aim Track debug [$state]"
        lines += "want ${rgb(s.targetColor)} h${s.hueTol} s${s.minSat} v${s.minVal}"
        if (info != null) {
            val t = info.target
            lines += "center ${rgb(info.centerRgb)}"
            lines += if (t != null) "blobs ${info.blobs} best ${t.cells}c ${rgb(t.rgb)}" else "blobs ${info.blobs}"
            lines += "aiming ${if (info.aiming) "YES" else "no"} off ${info.offX.toInt()},${info.offY.toInt()}"
            lines += "fps ${info.fps.toInt()} det ${"%.1f".format(info.detMs)}ms"
        }
        lines += if (State.lastDispatchMs == 0L) "swipe none"
        else "swipe ${State.lastSwipeX.toInt()},${State.lastSwipeY.toInt()} ${now - State.lastDispatchMs}ms ago"

        val p = 8f * d
        var maxW = 0f
        for (l in lines) maxW = max(maxW, txt.measureText(l))
        val lh = txt.fontSpacing
        c.drawRect(p, p, p * 3 + maxW, p * 3 + lh * lines.size, bg)
        for ((i, l) in lines.withIndex()) c.drawText(l, p * 2, p * 2 + i * lh - txt.ascent(), txt)
    }

    private fun rgb(i: Int) = "(${(i shr 16) and 0xFF},${(i shr 8) and 0xFF},${i and 0xFF})"

    /** Hue opposite the target so the overlay's own drawing cannot match the detector. */
    private fun debugColor(target: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(target, hsv)
        if (hsv[1] < 0.25f) return 0xFFFF00FF.toInt()
        hsv[0] = (hsv[0] + 180f) % 360f
        hsv[1] = 1f; hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }
}
