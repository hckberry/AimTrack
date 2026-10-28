package com.aimtrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private var cfg = AimSettings()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                )
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Store.load(this)
        State.settings = cfg

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "Assistive aim tool for single-player or private worlds only. " +
                "Do not use it on multiplayer servers."
            textSize = 14f
        })

        val pkg = EditText(this).apply {
            hint = "Target package"; setText(cfg.targetPackage); setSingleLine()
        }
        val color = EditText(this).apply {
            hint = "Highlight color RRGGBB"
            setText("%06X".format(cfg.targetColor and 0xFFFFFF)); setSingleLine()
        }

        col.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(pkg)
        col.addView(color)
        val presets = listOf(
            "Red" to "FF5555", "Gold" to "FFAA00", "Yellow" to "FFFF55", "Green" to "55FF55",
            "Aqua" to "55FFFF", "Blue" to "5555FF", "Pink" to "FF55FF", "White" to "FFFFFF"
        )
        col.addView(HorizontalScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                presets.forEach { (name, hex) -> addView(button(name) { color.setText(hex) }) }
            })
        })
        col.addView(button("Grant overlay permission (floating bubble)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2. Start (grant screen capture)") {
            commit(pkg.text.toString(), color.text.toString())
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            projectionLauncher.launch(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            )
        })
        col.addView(button("Pause / resume") { State.paused = !State.paused })
        col.addView(button("Stop") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })

        slider(col, "Assist strength", 0f, 1f, cfg.strength) { cfg = cfg.copy(strength = it); persist() }
        slider(col, "Gain (swipe px per offset px)", 0.2f, 2f, cfg.gain) { cfg = cfg.copy(gain = it); persist() }
        slider(col, "Smoothing", 0.05f, 1f, cfg.smoothing) { cfg = cfg.copy(smoothing = it); persist() }
        slider(col, "Deadzone (px)", 0f, 60f, cfg.deadzonePx) { cfg = cfg.copy(deadzonePx = it); persist() }
        slider(col, "Max step (px)", 20f, 300f, cfg.maxStepPx) { cfg = cfg.copy(maxStepPx = it); persist() }
        slider(col, "Cooldown (ms)", 0f, 400f, cfg.cooldownMs.toFloat()) { cfg = cfg.copy(cooldownMs = it.toLong()); persist() }
        slider(col, "Delay before swipe (ms)", 0f, 500f, cfg.delayMs.toFloat()) { cfg = cfg.copy(delayMs = it.toLong()); persist() }
        slider(col, "Hold duration (ms)", 20f, 200f, cfg.holdMs.toFloat()) { cfg = cfg.copy(holdMs = it.toLong()); persist() }
        slider(col, "Swipe speed (px/s)", 300f, 4000f, cfg.speedPxPerSec) { cfg = cfg.copy(speedPxPerSec = it); persist() }
        slider(col, "Color tolerance", 10f, 120f, cfg.tolerance.toFloat()) { cfg = cfg.copy(tolerance = it.toInt()); persist() }
        slider(col, "Detection region (fraction)", 0.15f, 0.6f, cfg.regionFraction) { cfg = cfg.copy(regionFraction = it); persist() }
        slider(col, "Swipe anchor X", 0.3f, 0.95f, cfg.anchorX) { cfg = cfg.copy(anchorX = it); persist() }
        slider(col, "Swipe anchor Y", 0.15f, 0.85f, cfg.anchorY) { cfg = cfg.copy(anchorY = it); persist() }

        slider(col, "Hue tolerance (degrees)", 4f, 40f, cfg.hueTol.toFloat()) { cfg = cfg.copy(hueTol = it.toInt()); persist() }
        slider(col, "Min saturation", 30f, 220f, cfg.minSat.toFloat()) { cfg = cfg.copy(minSat = it.toInt()); persist() }
        slider(col, "Min brightness", 30f, 220f, cfg.minVal.toFloat()) { cfg = cfg.copy(minVal = it.toInt()); persist() }
        slider(col, "Max blob size (region fraction)", 0.05f, 0.6f, cfg.maxCellsFraction) { cfg = cfg.copy(maxCellsFraction = it); persist() }
        slider(col, "Stable frames before aiming", 1f, 6f, cfg.stableFrames.toFloat()) { cfg = cfg.copy(stableFrames = it.toInt()); persist() }
        slider(col, "Lock radius (px)", 10f, 200f, cfg.lockRadiusPx) { cfg = cfg.copy(lockRadiusPx = it); persist() }
        slider(col, "Lock lost frames", 1f, 30f, cfg.lockLostFrames.toFloat()) { cfg = cfg.copy(lockLostFrames = it.toInt()); persist() }
        col.addView(CheckBox(this).apply {
            text = "Lock onto target until it disappears or moves away"; isChecked = cfg.lockEnabled
            setOnCheckedChangeListener { _, c -> cfg = cfg.copy(lockEnabled = c); persist() }
        })
        col.addView(CheckBox(this).apply {
            text = "Invert X"; isChecked = cfg.invertX
            setOnCheckedChangeListener { _, c -> cfg = cfg.copy(invertX = c); persist() }
        })
        col.addView(CheckBox(this).apply {
            text = "Invert Y"; isChecked = cfg.invertY
            setOnCheckedChangeListener { _, c -> cfg = cfg.copy(invertY = c); persist() }
        })

        col.addView(TextView(this).apply { text = "Debug and testing"; textSize = 16f; setPadding(0, 32, 0, 0) })
        col.addView(CheckBox(this).apply {
            text = "Debug overlay (region, offset, colors, dispatch flash)"; isChecked = State.debugOn
            setOnCheckedChangeListener { _, c -> State.debugOn = c }
        })
        col.addView(CheckBox(this).apply {
            text = "Test mode: fixed-interval swipes (right, left, down, up), ignores detection"
            isChecked = State.testMode
            setOnCheckedChangeListener { _, c -> State.testMode = c }
        })
        slider(col, "Test interval (ms)", 200f, 3000f, State.testIntervalMs.toFloat()) { State.testIntervalMs = it.toLong() }
        slider(col, "Test swipe length (px)", 20f, 300f, State.testSwipePx) { State.testSwipePx = it }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun slider(parent: LinearLayout, label: String, min: Float, max: Float,
                       value: Float, onChange: (Float) -> Unit) {
        val tv = TextView(this)
        fun show(v: Float) { tv.text = "%s: %.2f".format(label, v) }
        show(value)
        val sb = SeekBar(this).apply {
            this.max = 1000
            progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 1000f
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
    }

    private fun commit(pkg: String, hex: String) {
        val c = hex.trim().removePrefix("#").toIntOrNull(16)
        cfg = cfg.copy(
            targetPackage = pkg.trim().ifEmpty { cfg.targetPackage },
            targetColor = if (c != null) (0xFF000000.toInt() or c) else cfg.targetColor
        )
        persist()
    }

    private fun persist() {
        State.settings = cfg          // live: the running service reads this every frame
        Store.save(this, cfg)
    }
}
