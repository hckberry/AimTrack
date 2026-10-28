package com.aimtrack

import android.content.Context

/**
 * Units: every "Px" value below is in REFERENCE pixels (a 1080 px short side).
 * CaptureService converts to/from real screen pixels, so settings behave the same
 * on every resolution.
 */
data class AimSettings(
    // detection
    val targetColor: Int = 0xFFFF5555.toInt(),
    val tolerance: Int = 40,              // RGB distance; used only for white/gray targets
    val hueTol: Int = 14,                 // degrees, for saturated targets
    val minSat: Int = 100,                // 0..255
    val minVal: Int = 80,                 // 0..255
    val regionFraction: Float = 0.45f,    // region height as a fraction of screen height
    val sampleStep: Int = 4,              // reference px
    val minCells: Int = 6,
    val maxCellsFraction: Float = 0.25f,  // reject blobs bigger than this share of the region
    val stableFrames: Int = 2,
    // targeting
    val lockEnabled: Boolean = false,
    val lockRadiusPx: Float = 60f,
    val lockLostFrames: Int = 8,
    // aim
    val strength: Float = 0.6f,
    val gain: Float = 0.8f,
    val smoothing: Float = 0.4f,
    val deadzonePx: Float = 12f,
    val maxStepPx: Float = 120f,
    val cooldownMs: Long = 80,
    val delayMs: Long = 0,
    val holdMs: Long = 40,
    val speedPxPerSec: Float = 1500f,
    val anchorX: Float = 0.7f,
    val anchorY: Float = 0.5f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val targetPackage: String = "net.kdt.pojavlaunch"
)

/** Process-wide shared state; both services run in the same process. */
object State {
    @Volatile var settings = AimSettings()
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var gameFocused = false
    @Volatile var focusedSince = 0L
    @Volatile var lastTarget: TargetOffset? = null

    // debug / test (not persisted)
    @Volatile var debugOn = false
    @Volatile var testMode = false
    @Volatile var testIntervalMs = 1000L
    @Volatile var testSwipePx = 100f           // reference px
    @Volatile var debug: DebugInfo? = null
    @Volatile var lastDispatchMs = 0L
    @Volatile var lastSwipeX = 0f              // reference px
    @Volatile var lastSwipeY = 0f
}

object Store {
    private const val FILE = "aimtrack"

    fun load(c: Context): AimSettings {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = AimSettings()
        return AimSettings(
            targetColor = p.getInt("targetColor", d.targetColor),
            tolerance = p.getInt("tolerance", d.tolerance),
            hueTol = p.getInt("hueTol", d.hueTol),
            minSat = p.getInt("minSat", d.minSat),
            minVal = p.getInt("minVal", d.minVal),
            regionFraction = p.getFloat("regionFraction", d.regionFraction),
            sampleStep = p.getInt("sampleStep", d.sampleStep),
            minCells = p.getInt("minCells", d.minCells),
            maxCellsFraction = p.getFloat("maxCellsFraction", d.maxCellsFraction),
            stableFrames = p.getInt("stableFrames", d.stableFrames),
            lockEnabled = p.getBoolean("lockEnabled", d.lockEnabled),
            lockRadiusPx = p.getFloat("lockRadiusPx", d.lockRadiusPx),
            lockLostFrames = p.getInt("lockLostFrames", d.lockLostFrames),
            strength = p.getFloat("strength", d.strength),
            gain = p.getFloat("gain", d.gain),
            smoothing = p.getFloat("smoothing", d.smoothing),
            deadzonePx = p.getFloat("deadzonePx", d.deadzonePx),
            maxStepPx = p.getFloat("maxStepPx", d.maxStepPx),
            cooldownMs = p.getLong("cooldownMs", d.cooldownMs),
            delayMs = p.getLong("delayMs", d.delayMs),
            holdMs = p.getLong("holdMs", d.holdMs),
            speedPxPerSec = p.getFloat("speedPxPerSec", d.speedPxPerSec),
            anchorX = p.getFloat("anchorX", d.anchorX),
            anchorY = p.getFloat("anchorY", d.anchorY),
            invertX = p.getBoolean("invertX", d.invertX),
            invertY = p.getBoolean("invertY", d.invertY),
            targetPackage = p.getString("targetPackage", d.targetPackage) ?: d.targetPackage
        )
    }

    fun save(c: Context, s: AimSettings) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("targetColor", s.targetColor)
            .putInt("tolerance", s.tolerance)
            .putInt("hueTol", s.hueTol)
            .putInt("minSat", s.minSat)
            .putInt("minVal", s.minVal)
            .putFloat("regionFraction", s.regionFraction)
            .putInt("sampleStep", s.sampleStep)
            .putInt("minCells", s.minCells)
            .putFloat("maxCellsFraction", s.maxCellsFraction)
            .putInt("stableFrames", s.stableFrames)
            .putBoolean("lockEnabled", s.lockEnabled)
            .putFloat("lockRadiusPx", s.lockRadiusPx)
            .putInt("lockLostFrames", s.lockLostFrames)
            .putFloat("strength", s.strength)
            .putFloat("gain", s.gain)
            .putFloat("smoothing", s.smoothing)
            .putFloat("deadzonePx", s.deadzonePx)
            .putFloat("maxStepPx", s.maxStepPx)
            .putLong("cooldownMs", s.cooldownMs)
            .putLong("delayMs", s.delayMs)
            .putLong("holdMs", s.holdMs)
            .putFloat("speedPxPerSec", s.speedPxPerSec)
            .putFloat("anchorX", s.anchorX)
            .putFloat("anchorY", s.anchorY)
            .putBoolean("invertX", s.invertX)
            .putBoolean("invertY", s.invertY)
            .putString("targetPackage", s.targetPackage)
            .apply()
    }
}
