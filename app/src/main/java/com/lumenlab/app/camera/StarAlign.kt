package com.lumenlab.app.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lines up two star lists when the sky has turned a little between them. The stars are matched with a
 * turn (about the picture centre) and a shift: ref = turn(cur) + shift. The sky turns 15 degrees an hour,
 * so a few minutes already leave the edges of a stack several pixels off if only the shift is corrected.
 */
object StarAlign {
    class Pt(val x: Float, val y: Float)

    /** ref = R(theta) × (cur − centre) + centre + (tx, ty). Angles are in radians, turning clockwise on screen when positive. */
    class Similarity(val theta: Float, val tx: Float, val ty: Float, val matches: Int)

    /** The turn is ignored (treated as zero) below this, where even the far corner moves less than half a pixel. */
    fun negligibleAngle(width: Int, height: Int): Float =
        (0.5 / (0.5 * Math.hypot(width.toDouble(), height.toDouble()))).toFloat()

    fun estimate(
        ref: List<Pt>,
        cur: List<Pt>,
        cx: Float,
        cy: Float,
        maxShift: Int = 100,
        maxAngle: Float = 0.21f,
    ): Similarity? {
        if (ref.size < 8 || cur.size < 8) return null

        // First try the shift alone, which is enough for short bursts. The turn is then measured on the matches.
        voteShift(ref, cur, cx, cy, 0f, maxShift, 1)?.let { t ->
            refine(ref, cur, cx, cy, Similarity(0f, t.first, t.second, 0), floatArrayOf(3f, 2.5f, 2.5f), maxAngle)?.let { return it }
        }

        // Then try turning the stars in small steps and look for the turn that makes the most stars agree.
        var bestTheta = 0f
        var bestScore = 0
        var theta = -maxAngle
        val step = 0.005f
        while (theta <= maxAngle + 1e-6f) {
            val score = scoreShift(ref, cur, cx, cy, theta, maxShift, 6)
            if (score > bestScore) { bestScore = score; bestTheta = theta }
            theta += step
        }
        if (bestScore < 8) return null
        val t = voteShift(ref, cur, cx, cy, bestTheta, maxShift, 6) ?: return null
        return refine(ref, cur, cx, cy, Similarity(bestTheta, t.first, t.second, 0), floatArrayOf(9f, 5f, 3f, 2.5f, 2.5f), maxAngle)
    }

    private fun rotated(p: Pt, cx: Float, cy: Float, c: Float, s: Float): Pair<Float, Float> {
        val a = p.x - cx
        val b = p.y - cy
        return (c * a - s * b + cx) to (s * a + c * b + cy)
    }

    private fun histogram(ref: List<Pt>, cur: List<Pt>, cx: Float, cy: Float, theta: Float, maxShift: Int, bin: Int): Triple<IntArray, Int, Int> {
        val c = cos(theta)
        val s = sin(theta)
        val half = maxShift / bin
        val bins = 2 * half + 1
        val hist = IntArray(bins * bins)
        for (p in cur) {
            val (rx, ry) = rotated(p, cx, cy, c, s)
            for (r in ref) {
                val dx = ((r.x - rx) / bin).roundToInt()
                val dy = ((r.y - ry) / bin).roundToInt()
                if (abs(dx) <= half && abs(dy) <= half) hist[(dy + half) * bins + dx + half]++
            }
        }
        return Triple(hist, bins, half)
    }

    private fun bestWindow(hist: IntArray, bins: Int): Pair<Int, Int> {
        var best = -1
        var bestScore = 0
        for (y in 1 until bins - 1) {
            for (x in 1 until bins - 1) {
                var sum = 0
                for (j in -1..1) for (i in -1..1) sum += hist[(y + j) * bins + x + i]
                if (sum > bestScore) { bestScore = sum; best = y * bins + x }
            }
        }
        return best to bestScore
    }

    private fun scoreShift(ref: List<Pt>, cur: List<Pt>, cx: Float, cy: Float, theta: Float, maxShift: Int, bin: Int): Int {
        val (hist, bins, _) = histogram(ref, cur, cx, cy, theta, maxShift, bin)
        return bestWindow(hist, bins).second
    }

    /** The shift that lines up most stars after turning by [theta], or null when no shift is convincing. */
    private fun voteShift(ref: List<Pt>, cur: List<Pt>, cx: Float, cy: Float, theta: Float, maxShift: Int, bin: Int): Pair<Float, Float>? {
        val (hist, bins, half) = histogram(ref, cur, cx, cy, theta, maxShift, bin)
        val (best, score) = bestWindow(hist, bins)
        if (best < 0 || score < 8) return null
        // Several windows can tie, so the middle of the votes inside the window is used, not the window's own middle.
        val wx = best % bins
        val wy = best / bins
        var votes = 0
        var vx = 0f
        var vy = 0f
        for (j in -1..1) {
            for (i in -1..1) {
                val n = hist[(wy + j) * bins + wx + i]
                votes += n
                vx += n * ((wx + i - half) * bin.toFloat())
                vy += n * ((wy + j - half) * bin.toFloat())
            }
        }
        val bx = vx / votes
        val by = vy / votes
        // Average the pairs near the winning bin for a finer answer.
        val c = cos(theta)
        val s = sin(theta)
        var sx = 0f
        var sy = 0f
        var n = 0
        for (p in cur) {
            val (rx, ry) = rotated(p, cx, cy, c, s)
            for (r in ref) {
                val dx = r.x - rx
                val dy = r.y - ry
                if (abs(dx - bx) <= bin + 2f && abs(dy - by) <= bin + 2f) { sx += dx; sy += dy; n++ }
            }
        }
        return if (n >= 8) (sx / n) to (sy / n) else null
    }

    /** Improves a guess by matching each star to the nearest one and solving for the best turn and shift. */
    private fun refine(
        ref: List<Pt>,
        cur: List<Pt>,
        cx: Float,
        cy: Float,
        start: Similarity,
        tolerances: FloatArray,
        maxAngle: Float,
    ): Similarity? {
        var theta = start.theta
        var tx = start.tx
        var ty = start.ty
        var matches = 0
        for (tol in tolerances) {
            val c = cos(theta)
            val s = sin(theta)
            val ax = ArrayList<Float>()
            val ay = ArrayList<Float>()
            val bx = ArrayList<Float>()
            val by = ArrayList<Float>()
            for (p in cur) {
                val (rx, ry) = rotated(p, cx, cy, c, s)
                val mx = rx + tx
                val my = ry + ty
                var best: Pt? = null
                var bestD = tol * tol
                for (r in ref) {
                    val d = (r.x - mx) * (r.x - mx) + (r.y - my) * (r.y - my)
                    if (d <= bestD) { bestD = d; best = r }
                }
                if (best != null) {
                    ax += p.x - cx; ay += p.y - cy
                    bx += best.x - cx; by += best.y - cy
                }
            }
            matches = ax.size
            if (matches < 8) return null
            val mAx = ax.average().toFloat()
            val mAy = ay.average().toFloat()
            val mBx = bx.average().toFloat()
            val mBy = by.average().toFloat()
            var dot = 0.0
            var cross = 0.0
            for (i in 0 until matches) {
                val a1 = ax[i] - mAx
                val a2 = ay[i] - mAy
                val b1 = bx[i] - mBx
                val b2 = by[i] - mBy
                dot += a1 * b1 + a2 * b2
                cross += a1 * b2 - a2 * b1
            }
            theta = atan2(cross, dot).toFloat()
            val cc = cos(theta)
            val ss = sin(theta)
            tx = mBx - (cc * mAx - ss * mAy)
            ty = mBy - (ss * mAx + cc * mAy)
        }
        if (abs(theta) > maxAngle * 1.2f) return null
        return Similarity(theta, tx, ty, matches)
    }

    /**
     * Fills [dst] with [src] turned and shifted into the reference's place: dst(p) = src(q), where q is p carried
     * back through the transform. Parts that come from outside the picture are left black.
     */
    fun warp(src: IntArray, dst: IntArray, w: Int, h: Int, theta: Float, tx: Float, ty: Float, cx: Float, cy: Float) {
        val c = cos(theta)
        val s = sin(theta)
        for (y in 0 until h) {
            val v = y - cy - ty
            for (x in 0 until w) {
                val u = x - cx - tx
                val qx = c * u + s * v + cx
                val qy = -s * u + c * v + cy
                if (qx < 0f || qy < 0f || qx >= w - 1 || qy >= h - 1) {
                    dst[y * w + x] = 0xFF000000.toInt()
                    continue
                }
                val x0 = qx.toInt()
                val y0 = qy.toInt()
                val fx = qx - x0
                val fy = qy - y0
                val i = y0 * w + x0
                val p00 = src[i]; val p10 = src[i + 1]; val p01 = src[i + w]; val p11 = src[i + w + 1]
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                val r = (((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 + ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11 + 0.5f).toInt()
                val g = (((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 + ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11 + 0.5f).toInt()
                val b = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11 + 0.5f).toInt()
                dst[y * w + x] = (0xFF shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b, 255)
            }
        }
    }

    /** How many pixels to trim from the left and right edges, and from the top and bottom, to drop parts a turn of [theta] leaves empty. */
    fun marginX(theta: Float, w: Int, h: Int): Int = ceil(abs(theta) * h / 2.0 + theta * theta * w / 2.0).toInt() + 1
    fun marginY(theta: Float, w: Int, h: Int): Int = ceil(abs(theta) * w / 2.0 + theta * theta * h / 2.0).toInt() + 1

    /** Degrees, for messages. */
    fun degrees(theta: Float): Float = (theta * 180.0 / PI).toFloat()

    /** Largest of two angles by size, keeping the sign of the winner. */
    fun widest(a: Float, b: Float): Float = if (abs(b) > abs(a)) b else a

}
