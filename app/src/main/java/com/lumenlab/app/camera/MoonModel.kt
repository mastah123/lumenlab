package com.lumenlab.app.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Moon mode: exposure for a lit disk, finding the Moon in a photo, cropping around it and finishing the stack. */
object MoonModel {
    class Phase(val label: String, val exposureAtIso100Ns: Long)

    /** Rough exposure times at ISO 100 for a phone lens near f/1.8. The Moon is far brighter than the night sky around it. */
    val PHASES = listOf(
        Phase("Full", 1_000_000L),
        Phase("Gibbous", 2_000_000L),
        Phase("Quarter", 4_000_000L),
        Phase("Crescent", 16_000_000L),
    )

    /** Exposure time and ISO for a phase, at the camera's lowest ISO, moved by [ev] stops and kept inside the limits. */
    fun exposure(
        phase: Phase,
        ev: Float,
        minExposureNs: Long,
        maxExposureNs: Long,
        minIso: Int,
        maxIso: Int,
    ): Pair<Long, Int> {
        // The lowest ISO keeps the picture cleanest. If the shutter cannot go as fast as needed, it stays at its limit.
        val iso = minIso.coerceIn(minIso, maxIso)
        val wanted = phase.exposureAtIso100Ns * (100.0 / iso) * 2.0.pow(ev.toDouble())
        return wanted.toLong().coerceIn(minExposureNs, maxExposureNs) to iso
    }

    /** Where the Moon is in one photo: [cx], [cy] centre of light, [width] and [height] of its box. */
    class Found(val cx: Float, val cy: Float, val width: Int, val height: Int)

    /**
     * Finds the biggest bright area (the Moon). Pixels brighter than a share of the 99.9th percentile count; separate
     * bright areas such as street lamps are told apart by joining touching blocks and keeping the largest group.
     */
    fun find(luma: FloatArray, w: Int, h: Int): Found? {
        if (w < 16 || h < 16) return null
        val hist = IntArray(256)
        for (v in luma) hist[v.toInt().coerceIn(0, 255)]++
        var seen = 0
        var top = 255
        val limit = (luma.size * 0.001).toInt()
        while (top > 0) {
            seen += hist[top]
            if (seen > limit) break
            top--
        }
        if (top < 40) return null
        val thr = top * 0.45f
        val block = 4
        val bw = w / block
        val bh = h / block
        val on = BooleanArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var n = 0
            for (y in by * block until by * block + block) for (x in bx * block until bx * block + block) {
                if (luma[y * w + x] > thr) n++
            }
            on[by * bw + bx] = n >= block * block / 2
        }
        val label = IntArray(bw * bh)
        var best = -1
        var bestSize = 0
        var next = 0
        val queue = IntArray(bw * bh)
        for (start in on.indices) {
            if (!on[start] || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val c = queue[head++]
                val cx = c % bw
                val cy = c / bw
                for (d in 0 until 4) {
                    val nx = cx + intArrayOf(1, -1, 0, 0)[d]
                    val ny = cy + intArrayOf(0, 0, 1, -1)[d]
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
                    val ni = ny * bw + nx
                    if (on[ni] && label[ni] == 0) { label[ni] = next; queue[tail++] = ni }
                }
            }
            if (tail > bestSize) { bestSize = tail; best = next }
        }
        if (best < 0 || bestSize < 4) return null
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        for (i in label.indices) if (label[i] == best) {
            val x = i % bw * block
            val y = i / bw * block
            minX = min(minX, x); maxX = max(maxX, x + block)
            minY = min(minY, y); maxY = max(maxY, y + block)
        }
        // Centre of light inside the box, a little wider so the soft edge counts.
        val x0 = max(minX - block, 0)
        val x1 = min(maxX + block, w)
        val y0 = max(minY - block, 0)
        val y1 = min(maxY + block, h)
        var sum = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val v = luma[y * w + x] - thr
            if (v > 0f) { sum += v; sx += v * x; sy += v * y }
        }
        if (sum <= 0.0) return null
        return Found((sx / sum + 0.5).toFloat(), (sy / sum + 0.5).toFloat(), maxX - minX, maxY - minY)
    }

    /** Side of the square crop around the Moon: 1.6 times its size, even, between 128 and 1024 and no bigger than the picture. */
    fun cropSide(found: Found, w: Int, h: Int): Int {
        val side = (max(found.width, found.height) * 1.6f).roundToInt().coerceIn(128, 1024)
        return (min(side, min(w, h)) / 2) * 2
    }

    /** A crop window of [side] pixels around the Moon. [dx], [dy] are how far the Moon is from the window centre. */
    class Window(val x0: Int, val y0: Int, val dx: Float, val dy: Float)

    fun window(cx: Float, cy: Float, side: Int, w: Int, h: Int): Window {
        val x0 = (cx.roundToInt() - side / 2).coerceIn(0, max(w - side, 0))
        val y0 = (cy.roundToInt() - side / 2).coerceIn(0, max(h - side, 0))
        return Window(x0, y0, cx - x0 - side / 2f, cy - y0 - side / 2f)
    }

    /** The frames sharp enough to keep: the best [share] of them, but at least 3. */
    fun luckyFrames(sharpness: List<Double>, share: Float = 0.6f): List<Int> {
        val order = sharpness.indices.sortedByDescending { sharpness[it] }
        val n = max(min(3, sharpness.size), (sharpness.size * share).roundToInt())
        return order.take(min(n, sharpness.size))
    }

    /**
     * Finishing: sets the black point to the sky level and stretches so the bright detail of the Moon fills the range,
     * then sharpens a little. Works in place.
     */
    fun finish(px: IntArray, w: Int, h: Int, sharpen: Float) {
        val hist = IntArray(256)
        for (p in px) hist[(((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f).toInt().coerceIn(0, 255)]++
        fun percentile(q: Double): Int {
            var s = 0
            val target = px.size * q
            for (i in 0..255) { s += hist[i]; if (s >= target) return i }
            return 255
        }
        val low = percentile(0.05)
        val high = max(percentile(0.998), low + 20)
        val scale = 250f / (high - low)
        for (i in px.indices) {
            val p = px[i]
            fun ch(shift: Int) = ((((p shr shift) and 0xFF) - low) * scale + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        if (sharpen > 0f) SuperRes.sharpen(px, w, h, sharpen)
    }
}
