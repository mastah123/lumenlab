package com.lumenlab.app.camera

import android.graphics.Bitmap
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Stitches a row of overlapping photos into one wide picture. Each frame is warped onto a cylinder,
 * positioned from the turn recorded by the phone's rotation sensor, nudged into place by comparing
 * the overlap, and blended with soft seams. Exposure and white balance are locked while shooting,
 * so frames match.
 */
object Panorama {
    class Frame(val jpeg: ByteArray, val rotation: Int, val yaw: Float)
    class Result(val bitmap: Bitmap?, val message: String)

    private const val MAX_WIDTH = 9000
    private const val STEP_FRACTION = 0.65f

    /** Horizontal field of view of an upright (portrait) photo, from the sensor size and focal length. */
    fun horizontalFov(info: CameraInfo): Float {
        val physicalLong = info.physicalWidthMm
        val focal = info.focalLengthsMm.firstOrNull()
        if (physicalLong == null || focal == null || focal <= 0f || info.sensorWidth <= 0 || info.sensorHeight <= 0) {
            return Math.toRadians(60.0).toFloat()
        }
        val physicalShort = physicalLong * min(info.sensorWidth, info.sensorHeight) / max(info.sensorWidth, info.sensorHeight)
        return 2f * atan(physicalShort / (2f * focal))
    }

    suspend fun stitch(
        frames: List<Frame>,
        hFov: Float,
        hasSensor: Boolean,
        onProgress: (String, Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) { run(frames, hFov, hasSensor, onProgress) }

    private suspend fun run(
        frames: List<Frame>,
        hFov: Float,
        hasSensor: Boolean,
        onProgress: (String, Float) -> Unit,
    ): Result {
        val total = frames.size
        if (total < 2) return Result(null, "Not enough frames. Pan farther and try again.")
        // Keep the whole picture under about 9000 pixels wide.
        val frameWidth = min(1600, (MAX_WIDTH / (1f + (total - 1) * STEP_FRACTION)).toInt()).coerceAtLeast(480)

        val warped = ArrayList<IntArray>()
        val yaws = ArrayList<Float>()
        var w = 0
        var h = 0
        for ((i, frame) in frames.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress("Preparing frame ${i + 1} of $total", 0.3f * i / total)
            val decoded = DoubleExposure.decodeUpright(frame.jpeg, frame.rotation, (frameWidth * 1.4f).toInt()) ?: continue
            val scaled = if (decoded.width == frameWidth) decoded else Bitmap.createScaledBitmap(
                decoded, frameWidth, (decoded.height * frameWidth.toFloat() / decoded.width).roundToInt(), true,
            )
            if (scaled !== decoded) decoded.recycle()
            if (w == 0) {
                w = scaled.width
                h = scaled.height
            } else if (scaled.width != w || scaled.height != h) {
                scaled.recycle()
                continue
            }
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            scaled.recycle()
            warped += warpCylinder(px, w, h, hFov)
            yaws += frame.yaw
        }
        val n = warped.size
        if (n < 2) return Result(null, "Not enough usable frames.")

        val f = (w / 2f) / tan(hFov / 2f)
        // A cylinder leaves the top and bottom corners empty, so that much is trimmed away.
        val margin = ((1f - cos(hFov / 2f)) * h / 2f).roundToInt() + 2
        val luma = warped.map { lumaOf(it) }

        // Where each frame sits. The turn gives a first guess; the overlap decides the exact spot.
        val xs = IntArray(n)
        val ys = IntArray(n)
        for (i in 1 until n) {
            currentCoroutineContext().ensureActive()
            onProgress("Aligning frame $i of ${n - 1}", 0.3f + 0.3f * i / n)
            val predicted = (f * (yaws[i] - yaws[i - 1])).roundToInt().coerceIn(-(0.9f * w).toInt(), (0.9f * w).toInt())
            val rangeX = ((if (hasSensor) 0.15f else 0.30f) * w).toInt()
            val rangeY = (0.03f * h).toInt()
            val a = luma[i - 1]
            val b = luma[i]
            // The sensor's sign could be the other way round, so both directions are tried.
            val forward = search(a, b, w, h, margin, predicted, rangeX, rangeY)
            val backward = search(a, b, w, h, margin, -predicted, rangeX, rangeY)
            val best = if (backward.third < forward.third) backward else forward
            val guess = cost(a, b, w, h, margin, predicted, 0)
            // If matching is no better than the guess (plain sky, for example), trust the guess.
            val useGuess = best.third >= guess * 0.97
            xs[i] = xs[i - 1] + (if (useGuess) predicted else best.first)
            ys[i] = ys[i - 1] + (if (useGuess) 0 else best.second)
        }

        val baseX = xs.min()
        val canvasWidth = xs.max() - baseX + w
        val top = ys.max() + margin
        val bottom = ys.min() + h - margin
        val canvasHeight = bottom - top
        if (canvasHeight < 100) return Result(null, "The frames did not line up vertically. Hold the phone level and try again.")

        val out = IntArray(canvasWidth * canvasHeight)
        val cover = IntArray(n)
        val weight = IntArray(n)
        val localX = IntArray(n)
        for (x in 0 until canvasWidth) {
            if (x % 64 == 0) {
                currentCoroutineContext().ensureActive()
                onProgress("Blending", 0.6f + 0.35f * x / canvasWidth)
            }
            var count = 0
            for (i in 0 until n) {
                val lx = x - (xs[i] - baseX)
                if (lx in 0 until w) {
                    cover[count] = i
                    weight[count] = min(lx + 1, w - lx)
                    localX[count] = lx
                    count++
                }
            }
            for (y in 0 until canvasHeight) {
                var r = 0
                var g = 0
                var b = 0
                var sum = 0
                for (k in 0 until count) {
                    val i = cover[k]
                    val p = warped[i][(top + y - ys[i]) * w + localX[k]]
                    if ((p ushr 24) == 0) continue
                    val wt = weight[k]
                    r += wt * ((p shr 16) and 255)
                    g += wt * ((p shr 8) and 255)
                    b += wt * (p and 255)
                    sum += wt
                }
                out[y * canvasWidth + x] =
                    if (sum == 0) 0xFF000000.toInt() else (0xFF shl 24) or ((r / sum) shl 16) or ((g / sum) shl 8) or (b / sum)
            }
        }
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)
        return Result(bitmap, "Stitched $n frames.")
    }

    private fun mix(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float, shift: Int): Int {
        val a = (p00 shr shift) and 255
        val b = (p10 shr shift) and 255
        val c = (p01 shr shift) and 255
        val d = (p11 shr shift) and 255
        val top = a * (1f - fx) + b * fx
        val bottom = c * (1f - fx) + d * fx
        return (top * (1f - fy) + bottom * fy).toInt()
    }

    /** Bends a flat photo onto a cylinder, so turning the camera becomes a plain sideways shift. */
    private fun warpCylinder(src: IntArray, w: Int, h: Int, hFov: Float): IntArray {
        val f = (w / 2f) / tan(hFov / 2f)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val dest = IntArray(w * h)
        for (x in 0 until w) {
            val theta = (x - cx) / f
            val c = cos(theta)
            val sx = cx + f * tan(theta)
            val x0 = floor(sx).toInt()
            if (x0 < 0 || x0 >= w - 1) continue
            val fx = sx - x0
            for (y in 0 until h) {
                val sy = cy + (y - cy) / c
                val y0 = floor(sy).toInt()
                if (y0 < 0 || y0 >= h - 1) continue
                val fy = sy - y0
                val p00 = src[y0 * w + x0]
                val p10 = src[y0 * w + x0 + 1]
                val p01 = src[(y0 + 1) * w + x0]
                val p11 = src[(y0 + 1) * w + x0 + 1]
                dest[y * w + x] = (0xFF shl 24) or
                    (mix(p00, p10, p01, p11, fx, fy, 16) shl 16) or
                    (mix(p00, p10, p01, p11, fx, fy, 8) shl 8) or
                    mix(p00, p10, p01, p11, fx, fy, 0)
            }
        }
        return dest
    }

    private fun lumaOf(px: IntArray): ByteArray {
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            out[i] = ((77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8).toByte()
        }
        return out
    }

    /** Average brightness difference where frame b, moved by (dx, dy), overlaps frame a. Lower is a better match. */
    private fun cost(a: ByteArray, b: ByteArray, w: Int, h: Int, margin: Int, dx: Int, dy: Int): Double {
        val bx0 = max(0, -dx)
        val bx1 = min(w, w - dx)
        val by0 = max(margin, margin - dy)
        val by1 = min(h - margin, h - margin - dy)
        if (bx1 - bx0 < 40 || by1 - by0 < 40) return Double.MAX_VALUE
        var sum = 0L
        var count = 0
        var by = by0
        while (by < by1) {
            val rowB = by * w
            val rowA = (by + dy) * w + dx
            var bx = bx0
            while (bx < bx1) {
                val d = (a[rowA + bx].toInt() and 0xFF) - (b[rowB + bx].toInt() and 0xFF)
                sum += if (d < 0) -d else d
                count++
                bx += 4
            }
            by += 4
        }
        return if (count < 500) Double.MAX_VALUE else sum.toDouble() / count
    }

    /** Coarse search around a guess, then a fine search around the best spot. Returns dx, dy and the match cost. */
    private fun search(
        a: ByteArray, b: ByteArray, w: Int, h: Int, margin: Int, center: Int, rangeX: Int, rangeY: Int,
    ): Triple<Int, Int, Double> {
        var bestDx = center
        var bestDy = 0
        var best = Double.MAX_VALUE
        var dx = center - rangeX
        while (dx <= center + rangeX) {
            var dy = -rangeY
            while (dy <= rangeY) {
                val c = cost(a, b, w, h, margin, dx, dy)
                if (c < best) {
                    best = c
                    bestDx = dx
                    bestDy = dy
                }
                dy += 4
            }
            dx += 4
        }
        val cx = bestDx
        val cy = bestDy
        for (ddx in -3..3) {
            for (ddy in -3..3) {
                val c = cost(a, b, w, h, margin, cx + ddx, cy + ddy)
                if (c < best) {
                    best = c
                    bestDx = cx + ddx
                    bestDy = cy + ddy
                }
            }
        }
        return Triple(bestDx, bestDy, best)
    }
}
