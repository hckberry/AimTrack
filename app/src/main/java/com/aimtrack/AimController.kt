package com.aimtrack

import kotlin.math.hypot

/**
 * Turns target offsets (screen px) into at most one swipe vector at a time.
 * Smoothing, deadzone with hysteresis, pre-swipe delay, step clamp, cooldown.
 */
class AimController(private val settings: () -> AimSettings) {
    private var sx = 0f
    private var sy = 0f
    private var primed = false
    private var idle = true
    private var busy = false
    private var busySince = 0L
    private var readyAt = 0L
    private var eligibleSince = 0L

    /** Call when there is no target (or assist is paused). */
    @Synchronized fun lost() {
        primed = false; sx = 0f; sy = 0f; idle = true; eligibleSince = 0L
    }

    /** Returns a swipe vector (vx, vy) in screen px, or null if nothing should be sent now. */
    @Synchronized fun step(dx: Float, dy: Float, now: Long): FloatArray? {
        val s = settings()
        if (busy && now - busySince > 1000) busy = false   // watchdog: lost callback
        if (busy || now < readyAt) return null

        if (!primed) { sx = dx; sy = dy; primed = true }
        else { sx += s.smoothing * (dx - sx); sy += s.smoothing * (dy - sy) }

        val mag = hypot(sx, sy)
        val limit = if (idle) s.deadzonePx * 1.5f else s.deadzonePx
        if (mag < limit) { idle = true; eligibleSince = 0L; return null }
        idle = false

        // delay: offset must stay outside the deadzone for delayMs; the vector below
        // is computed from the latest smoothed offset at dispatch time
        if (eligibleSince == 0L) eligibleSince = now
        if (now - eligibleSince < s.delayMs) return null

        var vx = sx * s.gain * s.strength
        var vy = sy * s.gain * s.strength
        val vm = hypot(vx, vy)
        if (vm > s.maxStepPx) { vx *= s.maxStepPx / vm; vy *= s.maxStepPx / vm }
        if (s.invertX) vx = -vx
        if (s.invertY) vy = -vy

        busy = true; busySince = now; eligibleSince = 0L
        return floatArrayOf(vx, vy)
    }

    /** Call from the gesture completion/cancel callback. */
    @Synchronized fun onGestureDone(now: Long) {
        busy = false
        readyAt = now + settings().cooldownMs
        primed = false; sx = 0f; sy = 0f      // pre-swipe offsets are stale
    }
}
