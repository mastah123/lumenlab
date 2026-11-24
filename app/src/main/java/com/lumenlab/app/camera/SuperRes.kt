package com.lumenlab.app.camera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Super resolution from a burst of handheld photos. Hand shake moves each photo by a fraction of a pixel,
 * so together the photos sample the scene more finely than any one of them. The photos are lined up to the
 * sharpest one (shift only, no rotation) and every pixel of every photo is dropped onto a grid [scale] times finer.
 */
object SuperRes {
    /** Where a photo sits compared with the reference: reference(p) matches photo(p + shift). [error] is the leftover difference. */
    class Shift(val dx: Float, val dy: Float, val error: Float)

    /** Photos that moved more than this share of the picture width, or still differ a lot afterwards, are left out. */
    const val MAX_SHIFT_FRACTION = 0.12f
    const val MAX_ERROR = 22f

    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val p = argb[i]
        ((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f
    }

    /** Average absolute edge strength. The sharpest photo of the burst becomes the reference. */
    fun sharpness(luma: FloatArray, w: Int, h: Int): Double {
        if (w < 5 || h < 5) return 0.0
        var sum = 0.0
        var n = 0
        for (y in h / 8 until h - h / 8 step 2) {
            for (x in w / 8 until w - w / 8 step 2) {
                val c = luma[y * w + x]
                sum += abs(4 * c - luma[y * w + x - 1] - luma[y * w + x + 1] - luma[(y - 1) * w + x] - luma[(y + 1) * w + x])
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = min(cx.toInt(), w - 2)
        val y0 = min(cy.toInt(), h - 2)
        val fx = cx - x0
        val fy = cy - y0
        val i = y0 * w + x0
        return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + w] * (1 - fx) + a[i + w + 1] * fx) * fy
    }

    private fun downscale(a: FloatArray, w: Int, h: Int, k: Int): FloatArray {
        val cw = w / k
        val ch = h / k
        val out = FloatArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                var s = 0f
                for (j in 0 until k) for (i in 0 until k) s += a[(y * k + j) * w + x * k + i]
                out[y * cw + x] = s / (k * k)
            }
        }
        return out
    }

    /** Finds the shift of [frame] against [ref]: a coarse search on small copies, then a whole pixel search, then sub-pixel refinement. */
    fun estimateShift(ref: FloatArray, frame: FloatArray, w: Int, h: Int): Shift {
        // 1. Coarse, on copies a quarter the size.
        val cw = w / 4
        val ch = h / 4
        val rc = downscale(ref, w, h, 4)
        val fc = downscale(frame, w, h, 4)
        val r = max(1, min(12, min(cw, ch) / 4))
        class Candidate(val score: Double, val dx: Int, val dy: Int)
        val all = ArrayList<Candidate>()
        for (dy in -r..r) {
            for (dx in -r..r) {
                var sad = 0.0
                var n = 0
                for (y in r until ch - r) {
                    for (x in r until cw - r) {
                        sad += abs(rc[y * cw + x] - fc[(y + dy) * cw + x + dx])
                        n++
                    }
                }
                all += Candidate(if (n == 0) Double.MAX_VALUE else sad / n, dx, dy)
            }
        }
        all.sortBy { it.score }
        // Repeating patterns (fences, tiles) give several look-alike answers, so the best few are tried properly.
        val picked = ArrayList<Candidate>()
        for (c in all) {
            if (picked.size >= 4) break
            if (picked.none { abs(it.dx - c.dx) < 2 && abs(it.dy - c.dy) < 2 }) picked += c
        }

        // 2. Whole pixel search around each coarse answer. The best score wins.
        val m = max(8, (picked.maxOf { max(abs(it.dx), abs(it.dy)) } * 4 + 6))
        var bestFine = Double.MAX_VALUE
        var dx = 0f
        var dy = 0f
        for (cand in picked) {
            for (j in -3..3) {
                for (i in -3..3) {
                    val tx = cand.dx * 4f + i
                    val ty = cand.dy * 4f + j
                    var sad = 0.0
                    var n = 0
                    for (y in m until h - m step 3) {
                        for (x in m until w - m step 3) {
                            sad += abs(ref[y * w + x] - bilinear(frame, w, h, x + tx, y + ty))
                            n++
                        }
                    }
                    val score = if (n == 0) Double.MAX_VALUE else sad / n
                    if (score < bestFine) { bestFine = score; dx = tx; dy = ty }
                }
            }
        }

        // 3. Sub-pixel: Lucas-Kanade steps for a pure shift.
        for (iter in 0 until 8) {
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var bxs = 0.0; var bys = 0.0
            for (y in m until h - m step 2) {
                for (x in m until w - m step 2) {
                    val px = x + dx
                    val py = y + dy
                    val gx = (bilinear(frame, w, h, px + 1, py) - bilinear(frame, w, h, px - 1, py)) / 2
                    val gy = (bilinear(frame, w, h, px, py + 1) - bilinear(frame, w, h, px, py - 1)) / 2
                    val e = ref[y * w + x] - bilinear(frame, w, h, px, py)
                    sxx += gx * gx; syy += gy * gy; sxy += gx * gy
                    bxs += gx * e; bys += gy * e
                }
            }
            val det = sxx * syy - sxy * sxy
            if (det < 1e-6) break
            val ux = ((syy * bxs - sxy * bys) / det).toFloat()
            val uy = ((sxx * bys - sxy * bxs) / det).toFloat()
            dx += ux.coerceIn(-2f, 2f)
            dy += uy.coerceIn(-2f, 2f)
            if (abs(ux) + abs(uy) < 0.005f) break
        }

        var err = 0.0
        var n = 0
        for (y in m until h - m step 2) {
            for (x in m until w - m step 2) {
                err += abs(ref[y * w + x] - bilinear(frame, w, h, x + dx, y + dy))
                n++
            }
        }
        return Shift(dx, dy, if (n == 0) Float.MAX_VALUE else (err / n).toFloat())
    }

    /** True when a photo lined up well enough to use. */
    fun usable(shift: Shift, w: Int, maxError: Float = MAX_ERROR): Boolean =
        shift.error <= maxError && abs(shift.dx) <= w * MAX_SHIFT_FRACTION && abs(shift.dy) <= w * MAX_SHIFT_FRACTION

    /**
     * Merges photos onto a grid [scale] times finer (width × scale by height × scale). [shifts] has one entry per
     * photo, null for photos to skip. Returns ARGB pixels.
     */
    fun merge(
        frames: List<IntArray>,
        shifts: List<Shift?>,
        refIndex: Int,
        w: Int,
        h: Int,
        scale: Int,
        sharpen: Float = 0.35f,
        onProgress: (Float) -> Unit = {},
    ): IntArray {
        val ow = w * scale
        val oh = h * scale
        val out = IntArray(ow * oh)
        val ref = frames[refIndex]
        val bandRows = 96
        val accR = FloatArray(bandRows * ow)
        val accG = FloatArray(bandRows * ow)
        val accB = FloatArray(bandRows * ow)
        val wt = FloatArray(bandRows * ow)
        val prior = 0.1f
        var y0 = 0
        while (y0 < oh) {
            val y1 = min(oh, y0 + bandRows)
            accR.fill(0f); accG.fill(0f); accB.fill(0f); wt.fill(0f)
            for (fi in frames.indices) {
                val sh = shifts[fi] ?: continue
                val f = frames[fi]
                val frameWeight = if (fi == refIndex) 1.5f else 1f
                val vMin = (floor((y0 - 1 + 0.5f) / scale + sh.dy - 0.5f).toInt() - 1).coerceIn(0, h - 1)
                val vMax = (ceil((y1 + 0.5f) / scale + sh.dy - 0.5f).toInt() + 1).coerceIn(0, h - 1)
                for (v in vMin..vMax) {
                    val oy = (v - sh.dy + 0.5f) * scale - 0.5f
                    val iy = floor(oy).toInt()
                    val fy = oy - iy
                    if (iy + 1 < y0 || iy >= y1) continue
                    for (u in 0 until w) {
                        val ox = (u - sh.dx + 0.5f) * scale - 0.5f
                        val ix = floor(ox).toInt()
                        if (ix + 1 < 0 || ix >= ow) continue
                        val fx = ox - ix
                        val p = f[v * w + u]
                        val r = ((p shr 16) and 0xFF).toFloat()
                        val g = ((p shr 8) and 0xFF).toFloat()
                        val b = (p and 0xFF).toFloat()
                        for (j in 0..1) {
                            val yy = iy + j
                            if (yy < y0 || yy >= y1) continue
                            val wy = if (j == 0) 1 - fy else fy
                            for (i in 0..1) {
                                val xx = ix + i
                                if (xx < 0 || xx >= ow) continue
                                val ww = frameWeight * wy * (if (i == 0) 1 - fx else fx)
                                val idx = (yy - y0) * ow + xx
                                accR[idx] += r * ww; accG[idx] += g * ww; accB[idx] += b * ww; wt[idx] += ww
                            }
                        }
                    }
                }
            }
            for (yy in y0 until y1) {
                val sy = ((yy + 0.5f) / scale - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val ry = min(sy.toInt(), h - 2)
                val fyy = sy - ry
                for (xx in 0 until ow) {
                    val sx = ((xx + 0.5f) / scale - 0.5f).coerceIn(0f, (w - 1).toFloat())
                    val rx = min(sx.toInt(), w - 2)
                    val fxx = sx - rx
                    val i = ry * w + rx
                    val p00 = ref[i]; val p10 = ref[i + 1]; val p01 = ref[i + w]; val p11 = ref[i + w + 1]
                    fun chan(shift: Int): Float {
                        val a = (p00 shr shift) and 0xFF
                        val b = (p10 shr shift) and 0xFF
                        val c = (p01 shr shift) and 0xFF
                        val d = (p11 shr shift) and 0xFF
                        return (a * (1 - fxx) + b * fxx) * (1 - fyy) + (c * (1 - fxx) + d * fxx) * fyy
                    }
                    val idx = (yy - y0) * ow + xx
                    val total = wt[idx] + prior
                    val r = ((accR[idx] + prior * chan(16)) / total).toInt().coerceIn(0, 255)
                    val g = ((accG[idx] + prior * chan(8)) / total).toInt().coerceIn(0, 255)
                    val b = ((accB[idx] + prior * chan(0)) / total).toInt().coerceIn(0, 255)
                    out[yy * ow + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            y0 = y1
            onProgress(y0.toFloat() / oh)
        }
        if (sharpen > 0f) sharpen(out, ow, oh, sharpen)
        return out
    }

    /** A light unsharp mask, to win back some of the softness that spreading each pixel over its neighbours causes. */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        if (w < 3 || h < 3) return
        val prev = IntArray(w)
        val cur = IntArray(w)
        for (y in 0 until h) {
            System.arraycopy(px, y * w, cur, 0, w)
            val up = if (y == 0) cur else prev
            for (x in 0 until w) {
                val c = cur[x]
                val l = cur[max(x - 1, 0)]
                val r = cur[min(x + 1, w - 1)]
                val u = up[x]
                val d = if (y + 1 < h) px[(y + 1) * w + x] else c
                var result = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val cc = (c shr shift) and 0xFF
                    val avg = (((l shr shift) and 0xFF) + ((r shr shift) and 0xFF) + ((u shr shift) and 0xFF) + ((d shr shift) and 0xFF)) / 4f
                    val v = (cc + amount * (cc - avg)).toInt().coerceIn(0, 255)
                    result = result or (v shl shift)
                }
                px[y * w + x] = result
            }
            System.arraycopy(cur, 0, prev, 0, w)
        }
    }
}
