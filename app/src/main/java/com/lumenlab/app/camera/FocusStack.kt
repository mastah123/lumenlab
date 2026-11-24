package com.lumenlab.app.camera

import kotlin.math.abs
import kotlin.math.min

/**
 * Focus stacking: photos of the same scene focused at different distances are merged so everything is sharp.
 * Each photo is lined up to the reference, then every pixel is weighted by how sharp its neighbourhood is
 * in that photo. Flat areas look the same in every photo, so they simply average.
 */
object FocusStack {
    /** The distances to focus at, in diopters (1 ÷ metres), from the nearest end to the far end. */
    fun focusSteps(minFocusDiopters: Float, endFraction: Float, count: Int): List<Float> {
        if (count <= 1 || minFocusDiopters <= 0f) return listOf(0f)
        val end = minFocusDiopters * endFraction.coerceIn(0f, 1f)
        return List(count) { i -> minFocusDiopters + (end - minFocusDiopters) * i / (count - 1) }
    }

    /** [dst](p) = [src](p + shift), with soft edges and the border repeated. */
    fun warp(src: IntArray, w: Int, h: Int, dx: Float, dy: Float): IntArray {
        if (dx == 0f && dy == 0f) return src.copyOf()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0f, (h - 1).toFloat())
            val y0 = min(sy.toInt(), h - 2)
            val fy = sy - y0
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0f, (w - 1).toFloat())
                val x0 = min(sx.toInt(), w - 2)
                val fx = sx - x0
                val i = y0 * w + x0
                val p00 = src[i]; val p10 = src[i + 1]; val p01 = src[i + w]; val p11 = src[i + w + 1]
                var px = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val a = (p00 shr shift) and 0xFF
                    val b = (p10 shr shift) and 0xFF
                    val c = (p01 shr shift) and 0xFF
                    val d = (p11 shr shift) and 0xFF
                    val v = ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy + 0.5f).toInt().coerceIn(0, 255)
                    px = px or (v shl shift)
                }
                out[y * w + x] = px
            }
        }
        return out
    }

    /** How much fine detail surrounds each pixel: the edge strength averaged over a small square. */
    fun measure(luma: FloatArray, w: Int, h: Int, radius: Int = 3): FloatArray {
        val edge = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                edge[i] = abs(4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w])
            }
        }
        return boxBlur(edge, w, h, radius)
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val size = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var sum = 0f
            for (x in -r..r) sum += a[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / size
                sum += a[y * w + (x + r + 1).coerceAtMost(w - 1)] - a[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / size
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Collects photos one at a time, so only one needs to be in memory besides the running total. */
    class Accumulator(val w: Int, val h: Int) {
        private val r = FloatArray(w * h)
        private val g = FloatArray(w * h)
        private val b = FloatArray(w * h)
        private val wt = FloatArray(w * h)
        var count = 0
            private set

        /** Adds a photo that is already lined up with the reference. */
        fun add(aligned: IntArray) {
            val m = measure(SuperRes.luma(aligned), w, h)
            for (i in aligned.indices) {
                // Raising to the 4th power lets the sharpest photo clearly win where photos differ.
                val s = m[i] + 0.01f
                val weight = s * s * s * s
                val p = aligned[i]
                r[i] += ((p shr 16) and 0xFF) * weight
                g[i] += ((p shr 8) and 0xFF) * weight
                b[i] += (p and 0xFF) * weight
                wt[i] += weight
            }
            count++
        }

        fun result(): IntArray = IntArray(w * h) { i ->
            val total = wt[i]
            val rr = (r[i] / total + 0.5f).toInt().coerceIn(0, 255)
            val gg = (g[i] / total + 0.5f).toInt().coerceIn(0, 255)
            val bb = (b[i] / total + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }
}
