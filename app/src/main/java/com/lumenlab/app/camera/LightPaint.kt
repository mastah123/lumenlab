package com.lumenlab.app.camera

import kotlin.math.max
import kotlin.math.min

/**
 * Light painting: many photos are taken one after another and the brightest value of every pixel is kept, so a light
 * moved through the scene leaves a trail while the dark background stays dark. The phone must not move.
 */
object LightPaint {
    /** Pause between photos that the camera needs, used to estimate how many photos fit in the time. */
    const val OVERHEAD_NS = 250_000_000L

    fun framesFor(durationSeconds: Int, frameNs: Long): Int =
        ((durationSeconds * 1_000_000_000L) / (frameNs + OVERHEAD_NS)).toInt().coerceAtLeast(1)

    /** ISO for the chosen step, kept inside what the camera allows. */
    fun iso(wanted: Int, minIso: Int, maxIso: Int): Int = wanted.coerceIn(minIso, maxIso)

    /**
     * Keeps the brightest value of each colour of each pixel. A small [floor] is taken off every photo first, so the
     * sensor noise of the dark sky does not build up into a haze as photos are added.
     */
    class Accumulator(val w: Int, val h: Int, private val floor: Int = 8) {
        private val px = IntArray(w * h) { 0xFF000000.toInt() }
        var count = 0
            private set

        fun add(img: IntArray) {
            val scale = 255f / (255 - floor)
            for (i in 0 until w * h) {
                val p = img[i]
                val cur = px[i]
                var r = ((p shr 16) and 0xFF) - floor
                var g = ((p shr 8) and 0xFF) - floor
                var b = (p and 0xFF) - floor
                r = if (r <= 0) 0 else min((r * scale).toInt(), 255)
                g = if (g <= 0) 0 else min((g * scale).toInt(), 255)
                b = if (b <= 0) 0 else min((b * scale).toInt(), 255)
                px[i] = (0xFF shl 24) or
                    (max(r, (cur shr 16) and 0xFF) shl 16) or
                    (max(g, (cur shr 8) and 0xFF) shl 8) or
                    max(b, cur and 0xFF)
            }
            count++
        }

        fun result(): IntArray = px.copyOf()

        /** A small copy for showing while photos are still being taken. Returns pixels, width and height. */
        fun preview(maxSide: Int): Triple<IntArray, Int, Int> {
            val k = max(1, max(w, h) / maxSide)
            val pw = w / k
            val ph = h / k
            val out = IntArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) out[y * pw + x] = px[y * k * w + x * k]
            return Triple(out, pw, ph)
        }
    }
}
