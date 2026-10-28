package com.aimtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt

/** Foreground service: owns MediaProjection, capture, detection, tracking and the aim controller. */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.aimtrack.START"
        const val ACTION_STOP = "com.aimtrack.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.aimtrack.TOGGLE_PAUSE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "aimtrack"
        private const val NOTIF_ID = 1
        private const val CAPTURE_SCALE = 0.5f      // capture at half resolution for speed
        private const val REF_SIZE = 1080f          // reference short side for all "Px" settings
        private const val RESUME_SETTLE_MS = 300L   // ignore frames right after the game regains focus
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bubble: Bubble? = null
    private var debugOverlay: DebugOverlay? = null
    private val detector = TargetDetector()
    private val tracker = TargetTracker()
    private val controller = AimController { State.settings }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var screenW = 1f
    @Volatile private var refScale = 1f             // real screen px per reference px

    // debug stats (detect thread only)
    private var lastFrameNs = 0L
    private var fpsEma = 0f
    private var detEma = 0f

    // test mode
    @Volatile private var testBusy = false
    private var nextTestAt = 0L
    private var testIdx = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aim Track", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun togglePause() { State.paused = !State.paused; notifyUpdate() }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    private fun start(intent: Intent) {
        // Android 14+: must be a foreground service of type mediaProjection BEFORE getMediaProjection()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (State.running) return

        val data = readData(intent)
        if (data == null) { stopSelf(); return }
        val code = intent.getIntExtra(EXTRA_CODE, 0)

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        // Android 14+: a callback must be registered before creating the VirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        projection = mp

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenW = bounds.width().toFloat()
        refScale = minOf(bounds.width(), bounds.height()) / REF_SIZE
        val capW = (bounds.width() * CAPTURE_SCALE).toInt()
        val capH = (bounds.height() * CAPTURE_SCALE).toInt()

        val t = HandlerThread("aimtrack-detect").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, Handler(t.looper))
        reader = r
        display = mp.createVirtualDisplay(
            "aimtrack", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        bubble = Bubble(this) { togglePause() }.also { it.show() }
        debugOverlay = DebugOverlay(this).also { it.show() }
        State.paused = false
        State.running = true
    }

    private fun idle() {
        controller.lost(); tracker.reset()
        State.lastTarget = null; State.debug = null
        lastFrameNs = 0L
    }

    /** Detection loop: runs on the detect thread for every captured frame. */
    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            // auto-pause: paused, game not focused, or just regained focus (let the view settle)
            if (State.paused || !State.gameFocused || now - State.focusedSince < RESUME_SETTLE_MS) {
                idle(); return
            }
            val s = State.settings
            val rs = refScale

            // test mode: fixed-interval swipes, detection and controller bypassed
            if (State.testMode) { idle(); runTest(now, s, rs); return }

            val k = screenW / img.width                        // capture px -> screen px
            val debug = State.debugOn
            val step = max(1, (s.sampleStep * rs * CAPTURE_SCALE).roundToInt())

            val t0 = System.nanoTime()
            val candidates = detector.detect(img, s, step, debug)
            val detMs = (System.nanoTime() - t0) / 1e6f
            val t = tracker.update(candidates, s, s.lockRadiusPx * rs / k)
            State.lastTarget = t
            if (debug) publishDebug(img, candidates, t, k, rs, detMs)
            if (t == null) { controller.lost(); return }

            // the controller works in reference px, so tuning is resolution independent
            val v = controller.step(t.dx * k / rs, t.dy * k / rs, now) ?: return
            dispatch(v[0] * rs, v[1] * rs, s, rs) { controller.onGestureDone(SystemClock.uptimeMillis()) }
        } finally {
            img.close()
        }
    }

    /** Sends one swipe via the accessibility service (main thread) and records it for the debug overlay. */
    private fun dispatch(vx: Float, vy: Float, s: AimSettings, rs: Float, done: () -> Unit) {
        main.post {
            val svc = AimAccessibilityService.instance
            if (svc == null) { done(); return@post }
            State.lastSwipeX = vx / rs
            State.lastSwipeY = vy / rs
            State.lastDispatchMs = SystemClock.uptimeMillis()
            svc.swipe(vx, vy, s, rs, done)
        }
    }

    /** Right, left, down, up at a fixed interval. Raw vectors (no invert) so directions can be verified. */
    private fun runTest(now: Long, s: AimSettings, rs: Float) {
        if (testBusy || now < nextTestAt) return
        val dirs = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f))
        val d = dirs[testIdx]
        testIdx = (testIdx + 1) % dirs.size
        testBusy = true
        val len = State.testSwipePx * rs
        dispatch(d[0] * len, d[1] * len, s, rs) {
            testBusy = false
            nextTestAt = SystemClock.uptimeMillis() + State.testIntervalMs
        }
    }

    private fun publishDebug(img: Image, c: List<TargetOffset>, t: TargetOffset?, k: Float, rs: Float, detMs: Float) {
        val nowNs = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (nowNs - lastFrameNs) / 1e9f
            if (dt > 0f) fpsEma = if (fpsEma == 0f) 1f / dt else fpsEma * 0.9f + 0.1f / dt
        }
        lastFrameNs = nowNs
        detEma = if (detEma == 0f) detMs else detEma * 0.9f + detMs * 0.1f

        val shown = t ?: c.minByOrNull { it.dx.toLong() * it.dx + it.dy.toLong() * it.dy }
        State.debug = DebugInfo(
            centerRgb = detector.pixelRgb(img, img.width / 2, img.height / 2),
            blobs = c.size,
            target = shown,
            aiming = t != null,
            tx = (shown?.x ?: 0) * k, ty = (shown?.y ?: 0) * k,
            offX = (shown?.dx ?: 0) * k / rs, offY = (shown?.dy ?: 0) * k / rs,
            fps = fpsEma, detMs = detEma
        )
    }

    private fun buildNotification(): Notification {
        fun action(label: String, act: String, icon: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, CaptureService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Aim Track")
            .setContentText(if (State.paused) "Paused" else "Active while the game is focused")
            .setOngoing(true)
            .addAction(action(if (State.paused) "Resume" else "Pause", ACTION_TOGGLE_PAUSE, android.R.drawable.ic_media_pause))
            .addAction(action("Stop", ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    private fun notifyUpdate() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())

    override fun onDestroy() {
        State.running = false
        State.testMode = false          // never resume test swipes on the next start
        State.lastTarget = null
        State.debug = null
        bubble?.hide()
        debugOverlay?.hide()
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}
