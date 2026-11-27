package com.lumenlab.app.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Joins a sharp ground picture and a clean sky picture along a horizon line. The sky is the star-aligned stack
 * (noise down, stars sharp); the ground is the plain average (the ground did not move, so it is sharp there).
 */
object MilkyBlend {
    /** How much of the sky picture to use on row [y]. 1 above the horizon band, 0 below it, smooth in between. */
    fun skyShare(y: Int, h: Int, horizon: Float, feather: Float): Float {
        val t = (y + 0.5f) / h
        if (feather <= 0f) return if (t < horizon) 1f else 0f
        val u = ((t - (horizon - feather)) / (2 * feather)).coerceIn(0f, 1f)
        val smooth = u * u * (3 - 2 * u)
        return 1f - smooth
    }

    fun blend(sky: IntArray, ground: IntArray, w: Int, h: Int, horizon: Float, feather: Float): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val s = skyShare(y, h, horizon, feather)
            for (x in 0 until w) {
                val i = y * w + x
                if (s >= 1f) { out[i] = sky[i]; continue }
                if (s <= 0f) { out[i] = ground[i]; continue }
                val a = sky[i]
                val b = ground[i]
                fun ch(shift: Int) = ((((a shr shift) and 0xFF) * s + ((b shr shift) and 0xFF) * (1 - s)) + 0.5f).toInt()
                out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }

    /**
     * Brings out the Milky Way in the sky picture: the sky level becomes black, mid tones are lifted and colour is
     * strengthened. [boost] runs from 0 (only the black point) to 1 (strongest). Works in place. The levels are
     * measured on the first [sampleCount] pixels (the rows above the horizon), so a dark ground does not set the black point.
     */
    fun tone(px: IntArray, boost: Float, sampleCount: Int = px.size) {
        val hist = IntArray(256)
        val count = sampleCount.coerceIn(1, px.size)
        for (j in 0 until count) {
            val p = px[j]
            hist[(((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f).toInt().coerceIn(0, 255)]++
        }
        var s = 0
        var low = 0
        val target = count * 0.1
        for (i in 0..255) { s += hist[i]; if (s >= target) { low = i; break } }
        val range = 255f - low
        val gamma = 1f / (1f + 0.9f * boost.coerceIn(0f, 1f))
        val lut = IntArray(256) { v -> (255f * ((max(v - low, 0)) / range).pow(gamma) + 0.5f).toInt().coerceIn(0, 255) }
        val sat = 1f + 0.5f * boost.coerceIn(0f, 1f)
        for (i in px.indices) {
            val p = px[i]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            val l = r * 0.299f + g * 0.587f + b * 0.114f
            val rr = (l + (r - l) * sat + 0.5f).toInt().coerceIn(0, 255)
            val gg = (l + (g - l) * sat + 0.5f).toInt().coerceIn(0, 255)
            val bb = (l + (b - l) * sat + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /** Where the sky meets the ground, as a share of the height from the top: the row where brightness changes most. */
    fun guessHorizon(px: IntArray, w: Int, h: Int): Float {
        if (h < 20 || w < 4) return 0.6f
        val rows = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w step 2) {
                val p = px[y * w + x]
                sum += ((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f
            }
            rows[y] = sum / ((w + 1) / 2)
        }
        val k = max(3, h / 40)
        var best = 0f
        var bestY = (h * 0.6f).toInt()
        for (y in (h * 0.2f).toInt() until (h * 0.9f).toInt()) {
            if (y - k < 0 || y + k >= h) continue
            var above = 0f
            var below = 0f
            for (j in 1..k) { above += rows[y - j]; below += rows[y + j] }
            val d = abs(above - below) / k
            if (d > best) { best = d; bestY = y }
        }
        return (bestY + 0.5f) / h
    }

    /** Shrinks by an whole factor [k] by averaging k × k blocks. Returns the pixels, width and height. */
    fun downscale(px: IntArray, w: Int, h: Int, k: Int): Triple<IntArray, Int, Int> {
        if (k <= 1) return Triple(px.copyOf(), w, h)
        val nw = w / k
        val nh = h / k
        val out = IntArray(nw * nh)
        val n = k * k
        for (y in 0 until nh) for (x in 0 until nw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until k) for (dx in 0 until k) {
                val p = px[(y * k + dy) * w + x * k + dx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            out[y * nw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Triple(out, nw, nh)
    }
}
