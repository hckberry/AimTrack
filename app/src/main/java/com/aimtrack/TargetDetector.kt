package com.aimtrack

import android.media.Image

/** Coordinates are in capture-pixel space. dx/dy are relative to the frame center (the crosshair). */
data class TargetOffset(
    val x: Int, val y: Int,
    val dx: Int, val dy: Int,
    val cells: Int,
    val timestampNs: Long,
    val rgb: Int = 0              // average blob color (only filled when collectColor = true)
)

/**
 * Color detection around the crosshair, robust version:
 *  - saturated targets: hue window + minimum saturation/brightness (survives lighting changes)
 *  - white/gray targets: RGB distance
 *  - connected components, then size filters (too small = noise, too big = sky/terrain)
 * Returns every surviving blob (max 16); TargetTracker picks one. Expects RGBA_8888.
 */
class TargetDetector {
    private var mask = BooleanArray(0)
    private var stack = IntArray(0)

    fun detect(img: Image, s: AimSettings, step: Int, collectColor: Boolean = false): List<TargetOffset> {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = img.width
        val h = img.height

        // region: height-based so it is the same physical area on every resolution/aspect ratio
        val rh = (h * s.regionFraction).toInt().coerceIn(1, h)
        val rw = minOf(w, (rh * 1.6f).toInt())
        val left = (w - rw) / 2
        val top = (h - rh) / 2
        val gw = rw / step
        val gh = rh / step
        val total = gw * gh
        if (total == 0) return emptyList()
        if (mask.size < total) {
            mask = BooleanArray(total)
            stack = IntArray(total)
        }

        val tr = (s.targetColor shr 16) and 0xFF
        val tg = (s.targetColor shr 8) and 0xFF
        val tb = s.targetColor and 0xFF
        val tp = hsv(tr, tg, tb)
        val tHue = tp shr 16
        val grayTarget = ((tp shr 8) and 0xFF) < 60
        val tol2 = s.tolerance * s.tolerance

        // 1) classify sampled pixels
        for (gy in 0 until gh) {
            val rowBase = (top + gy * step) * rowStride
            for (gx in 0 until gw) {
                val i = rowBase + (left + gx * step) * pixStride
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                mask[gy * gw + gx] =
                    if (grayTarget) {
                        val dr = r - tr; val dg = g - tg; val db = b - tb
                        dr * dr + dg * dg + db * db <= tol2
                    } else {
                        val p = hsv(r, g, b)
                        var dh = Math.abs((p shr 16) - tHue)
                        if (dh > 180) dh = 360 - dh
                        dh <= s.hueTol && ((p shr 8) and 0xFF) >= s.minSat && (p and 0xFF) >= s.minVal
                    }
            }
        }

        // 2) blobs (4-connected flood fill) with size filters
        val maxCells = (total * s.maxCellsFraction).toInt()
        val cxF = w / 2f
        val cyF = h / 2f
        val out = ArrayList<TargetOffset>(4)

        for (start in 0 until total) {
            if (!mask[start]) continue
            var sp = 0
            stack[sp++] = start
            mask[start] = false
            var n = 0
            var sx = 0L
            var sy = 0L
            var ar = 0
            var ag = 0
            var ab = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % gw
                val cy = c / gw
                n++; sx += cx; sy += cy
                if (collectColor) {
                    val i = (top + cy * step) * rowStride + (left + cx * step) * pixStride
                    ar += buf.get(i).toInt() and 0xFF
                    ag += buf.get(i + 1).toInt() and 0xFF
                    ab += buf.get(i + 2).toInt() and 0xFF
                }
                if (cx > 0 && mask[c - 1]) { mask[c - 1] = false; stack[sp++] = c - 1 }
                if (cx < gw - 1 && mask[c + 1]) { mask[c + 1] = false; stack[sp++] = c + 1 }
                if (cy > 0 && mask[c - gw]) { mask[c - gw] = false; stack[sp++] = c - gw }
                if (cy < gh - 1 && mask[c + gw]) { mask[c + gw] = false; stack[sp++] = c + gw }
            }
            if (n < s.minCells || n > maxCells || out.size >= 16) continue
            val px = left + (sx.toFloat() / n) * step + step / 2f
            val py = top + (sy.toFloat() / n) * step + step / 2f
            val rgb = if (collectColor) ((ar / n) shl 16) or ((ag / n) shl 8) or (ab / n) else 0
            out.add(TargetOffset(px.toInt(), py.toInt(), (px - cxF).toInt(), (py - cyF).toInt(), n, img.timestamp, rgb))
        }
        return out
    }

    /** RGB (0xRRGGBB) of one pixel, for the debug log. */
    fun pixelRgb(img: Image, x: Int, y: Int): Int {
        val p = img.planes[0]
        val cx = x.coerceIn(0, img.width - 1)
        val cy = y.coerceIn(0, img.height - 1)
        val i = cy * p.rowStride + cx * p.pixelStride
        val b = p.buffer
        return ((b.get(i).toInt() and 0xFF) shl 16) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i + 2).toInt() and 0xFF)
    }

    /** Packed (hue 0..360) << 16 | (sat 0..255) << 8 | (value 0..255). Integer math only. */
    private fun hsv(r: Int, g: Int, b: Int): Int {
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        val d = mx - mn
        val sat = if (mx == 0) 0 else d * 255 / mx
        var h = 0
        if (d != 0) {
            h = when (mx) {
                r -> 60 * (g - b) / d
                g -> 120 + 60 * (b - r) / d
                else -> 240 + 60 * (r - g) / d
            }
            if (h < 0) h += 360
        }
        return (h shl 16) or (sat shl 8) or mx
    }
}
