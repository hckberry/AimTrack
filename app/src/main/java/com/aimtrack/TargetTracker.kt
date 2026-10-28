package com.aimtrack

/**
 * Picks one target from the detector's candidates.
 *  - default: closest blob to the crosshair, every frame
 *  - stability: a target must persist (within the association radius) for stableFrames frames
 *  - lock (optional): keep following the same entity as it moves; release when it disappears for
 *    lockLostFrames frames, or jumps farther than the association radius between frames
 */
class TargetTracker {
    private var has = false
    private var lastX = 0f
    private var lastY = 0f
    private var streak = 0
    private var missed = 0

    fun reset() { has = false; streak = 0; missed = 0 }

    /** [radius] is the association radius in capture px. */
    fun update(c: List<TargetOffset>, s: AimSettings, radius: Float): TargetOffset? {
        val r2 = radius * radius
        var chosen: TargetOffset? = c.minByOrNull { it.dx.toLong() * it.dx + it.dy.toLong() * it.dy }

        if (s.lockEnabled && has) {
            val near = c.minByOrNull { d2(it) }
            if (near != null && d2(near) <= r2) {
                chosen = near; missed = 0
            } else if (missed < s.lockLostFrames) {
                missed++; return null           // hold the lock briefly (flicker, occlusion)
            } else {
                has = false; missed = 0         // released: entity gone or moved too far
            }
        }

        if (chosen == null) { reset(); return null }
        streak = if (has && d2(chosen) <= r2) streak + 1 else 1
        has = true
        lastX = chosen.x.toFloat(); lastY = chosen.y.toFloat()
        return if (streak >= s.stableFrames) chosen else null
    }

    private fun d2(t: TargetOffset): Float {
        val dx = t.x - lastX
        val dy = t.y - lastY
        return dx * dx + dy * dy
    }
}
