package com.aimtrack

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Draggable overlay bubble. Tap = pause/resume. Needs the "display over other apps" permission.
 * ON (green) = active, II (amber) = paused, -- (gray) = game not focused.
 * Note: overlays are captured by MediaProjection too; keep the bubble out of the detection region.
 */
class Bubble(private val ctx: Context, private val onToggle: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val lp: WindowManager.LayoutParams

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 400) }
    }

    init {
        val size = (52 * ctx.resources.displayMetrics.density).toInt()
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 160 }
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val v = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF707070.toInt()) }
            setOnTouchListener(object : View.OnTouchListener {
                var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (abs(dx) > 10 || abs(dy) > 10) moved = true
                            if (moved) {
                                lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                                wm.updateViewLayout(v, lp)
                            }
                        }
                        MotionEvent.ACTION_UP -> if (!moved) { onToggle(); refresh() }
                    }
                    return true
                }
            })
        }
        wm.addView(v, lp)
        view = v
        refresh()
        ui.post(tick)
    }

    fun hide() {
        ui.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun refresh() {
        val v = view ?: return
        val (label, color) = when {
            State.paused -> "II" to 0xFFE0A000.toInt()
            !State.gameFocused -> "--" to 0xFF707070.toInt()
            else -> "ON" to 0xFF2E9E4F.toInt()
        }
        v.text = label
        (v.background as GradientDrawable).setColor(color)
    }
}
