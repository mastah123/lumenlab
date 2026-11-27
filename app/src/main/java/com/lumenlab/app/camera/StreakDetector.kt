package com.lumenlab.app.camera

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds meteor, satellite and aeroplane streaks in a night photo. Stars are round and slow, so a long thin bright
 * line that was not in the previous photo is a streak. What it cannot tell is which kind of streak it is.
 */
object StreakDetector {
    class Streak(val cx: Float, val cy: Float, val length: Float, val angleDeg: Float, val pixels: Int)

    /**
     * Pixels that stand out from their surroundings. A wide blur is taken off first, so a glow, the Milky Way or a lit
     * ground does not count; what is left is compared with the noise ([sigma] times its spread, and at least 15 levels).
     */
    fun mask(luma: FloatArray, w: Int, h: Int, sigma: Float = 5f): BooleanArray {
        val r = 12
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += luma[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val high = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = minOf(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                high[y * w + x] = luma[y * w + x] - (sum / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        // The noise level from the middle of the values, so stars and streaks do not raise it.
        val sample = FloatArray(high.size / 5 + 1) { high[minOf(it * 5, high.size - 1)] }
        sample.sort()
        val median = sample[sample.size / 2]
        val deviations = FloatArray(sample.size) { kotlin.math.abs(sample[it] - median) }
        deviations.sort()
        val spread = 1.4826f * deviations[deviations.size / 2]
        val threshold = max(median + sigma * spread, 15f)
        return BooleanArray(w * h) { high[it] > threshold }
    }

    /** Grows every set pixel by [r] pixels in each direction. */
    fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (src[row + x]) for (k in max(0, x - r)..minOf(w - 1, x + r)) tmp[row + k] = true
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) if (tmp[y * w + x]) for (k in max(0, y - r)..minOf(h - 1, y + r)) out[k * w + x] = true
        }
        return out
    }

    /**
     * Streaks among the bright pixels of [mask] that are not near a bright pixel of the previous photo.
     * [previousDilated] is the previous photo's mask grown by a few pixels (stars drift a little between photos).
     */
    fun detect(
        mask: BooleanArray,
        previousDilated: BooleanArray?,
        w: Int,
        h: Int,
        minLength: Float = 40f,
        minPixels: Int = 30,
        minElongation: Float = 10f,
        maxThickness: Float = 10f,
    ): List<Streak> {
        val novel = BooleanArray(w * h) { mask[it] && (previousDilated == null || !previousDilated[it]) }
        val link = dilate(novel, w, h, 2)
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        var labels = 0
        val dx = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val dy = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        for (start in link.indices) {
            if (!link[start] || label[start] != 0) continue
            labels++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = labels
            while (head < tail) {
                val c = queue[head++]
                val cx = c % w
                val cy = c / w
                for (d in 0 until 8) {
                    val nx = cx + dx[d]
                    val ny = cy + dy[d]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (link[ni] && label[ni] == 0) { label[ni] = labels; queue[tail++] = ni }
                }
            }
        }
        if (labels == 0) return emptyList()
        val n = IntArray(labels + 1)
        val sx = DoubleArray(labels + 1)
        val sy = DoubleArray(labels + 1)
        val sxx = DoubleArray(labels + 1)
        val syy = DoubleArray(labels + 1)
        val sxy = DoubleArray(labels + 1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!novel[i]) continue
            val l = label[i]
            n[l]++
            sx[l] += x; sy[l] += y
            sxx[l] += x.toDouble() * x; syy[l] += y.toDouble() * y; sxy[l] += x.toDouble() * y
        }
        val out = ArrayList<Streak>()
        for (l in 1..labels) {
            if (n[l] < minPixels) continue
            val mx = sx[l] / n[l]
            val my = sy[l] / n[l]
            val vxx = sxx[l] / n[l] - mx * mx
            val vyy = syy[l] / n[l] - my * my
            val vxy = sxy[l] / n[l] - mx * my
            val tr = vxx + vyy
            val det = vxx * vyy - vxy * vxy
            val disc = sqrt(max(tr * tr / 4 - det, 0.0))
            val l1 = tr / 2 + disc
            val l2 = max(tr / 2 - disc, 1.0 / 12.0)
            val length = sqrt(12.0 * l1).toFloat()
            if (length < minLength) continue
            if (l1 / l2 < minElongation) continue
            if (n[l] / length > maxThickness) continue
            val angle = Math.toDegrees(0.5 * atan2(2 * vxy, vxx - vyy)).toFloat()
            out += Streak(mx.toFloat(), my.toFloat(), length, angle, n[l])
        }
        return out
    }
}
