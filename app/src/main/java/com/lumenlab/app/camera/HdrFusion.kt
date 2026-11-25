package com.lumenlab.app.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The exposures of an HDR bracket: the camera's own pick, then darker and brighter by whole or half stops. */
object Bracket {
    class Shot(val exposureNs: Long, val iso: Int, val ev: Float)

    /** EV steps for [count] photos (3 or 5) with [step] stops between them, darkest first. */
    fun evSteps(count: Int, step: Float): List<Float> {
        val half = count / 2
        return List(count) { (it - half) * step }
    }

    /**
     * The exposure time and ISO for each step. The time is changed first. If the camera cannot go
     * that far, the rest of the change is made with ISO.
     */
    fun plan(
        baseExposureNs: Long,
        baseIso: Int,
        evs: List<Float>,
        minExposureNs: Long,
        maxExposureNs: Long,
        minIso: Int,
        maxIso: Int,
    ): List<Shot> = evs.map { ev ->
        val factor = 2.0.pow(ev.toDouble())
        val wanted = baseExposureNs * factor
        val exposure = wanted.toLong().coerceIn(minExposureNs, maxExposureNs)
        val leftover = factor / (exposure.toDouble() / baseExposureNs)
        val iso = (baseIso * leftover).toInt().coerceIn(minIso, maxIso)
        Shot(exposure, iso, ev)
    }
}

/**
 * Exposure fusion (Mertens, Kautz and Van Reeth): photos taken at different exposures are blended so each part of
 * the picture comes mostly from the photo where it is well exposed, detailed and colourful. The blend is done on
 * layers of different sizes, so the joins do not show. No camera response curve is needed.
 * Use in two passes: [addWeights] for every photo, then [accumulate] for every photo, then [result].
 */
class HdrFuser(val w: Int, val h: Int) {
    private val levels: Int
    private val sizes = ArrayList<Pair<Int, Int>>()
    private val weights = ArrayList<FloatArray>()
    private val weightSum = FloatArray(w * h)
    private var fused: Array<Array<FloatArray>>? = null

    init {
        var lw = w
        var lh = h
        var n = 1
        sizes += lw to lh
        while (n < 7 && min(lw, lh) >= 32) {
            lw = (lw + 1) / 2
            lh = (lh + 1) / 2
            sizes += lw to lh
            n++
        }
        levels = n
    }

    /** First pass: works out how good each pixel of this photo is. Returns the photo's index. */
    fun addWeights(img: IntArray): Int {
        val wt = weightMap(img, w, h)
        for (i in wt.indices) weightSum[i] += wt[i]
        weights += wt
        return weights.size - 1
    }

    /** Second pass: blends photo [index] into the result. */
    fun accumulate(index: Int, img: IntArray) {
        val sum = weightSum
        val normalised = FloatArray(w * h) { weights[index][it] / sum[it] }
        val weightPyramid = gaussian(normalised)
        val target = fused ?: Array(3) { Array(levels) { l -> FloatArray(sizes[l].first * sizes[l].second) } }.also { fused = it }
        for (ch in 0 until 3) {
            val shift = 16 - 8 * ch
            val plane = FloatArray(w * h) { ((img[it] shr shift) and 0xFF) / 255f }
            val g = gaussian(plane)
            for (l in 0 until levels) {
                val (lw, lh) = sizes[l]
                val detail = if (l == levels - 1) g[l] else {
                    val up = expand(g[l + 1], sizes[l + 1].first, sizes[l + 1].second, lw, lh)
                    FloatArray(lw * lh) { g[l][it] - up[it] }
                }
                val out = target[ch][l]
                val wl = weightPyramid[l]
                for (i in out.indices) out[i] += wl[i] * detail[i]
            }
        }
        weights[index] = FloatArray(0)
    }

    fun result(): IntArray {
        val target = fused ?: return IntArray(w * h)
        val planes = Array(3) { ch ->
            var cur = target[ch][levels - 1]
            for (l in levels - 2 downTo 0) {
                val up = expand(cur, sizes[l + 1].first, sizes[l + 1].second, sizes[l].first, sizes[l].second)
                val layer = target[ch][l]
                cur = FloatArray(layer.size) { layer[it] + up[it] }
            }
            cur
        }
        return IntArray(w * h) { i ->
            val r = (planes[0][i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (planes[1][i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (planes[2][i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun gaussian(base: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        out += base
        for (l in 1 until levels) out += reduce(out[l - 1], sizes[l - 1].first, sizes[l - 1].second)
        return out
    }

    companion object {
        /** Brightness made comparable between photos: average 0 and spread 1, so a dark and a bright photo can be lined up. */
        fun normalise(luma: FloatArray): FloatArray {
            var sum = 0.0
            for (v in luma) sum += v
            val mean = sum / luma.size
            var sq = 0.0
            for (v in luma) sq += (v - mean) * (v - mean)
            val sd = max(sqrt(sq / luma.size), 1e-3)
            return FloatArray(luma.size) { ((luma[it] - mean) / sd).toFloat() }
        }

        /** How much each pixel should count: detail, colour and being neither too dark nor too bright all raise it. */
        fun weightMap(img: IntArray, w: Int, h: Int): FloatArray {
            val gray = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (i in img.indices) {
                val p = img[i]
                gray[i] = (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val p = img[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    val contrast = if (x in 1 until w - 1 && y in 1 until h - 1) {
                        abs(4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w])
                    } else 0f
                    val mean = (r + g + b) / 3f
                    val saturation = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f)
                    val well = exp(-((r - 0.5f) * (r - 0.5f)) / 0.08f) *
                        exp(-((g - 0.5f) * (g - 0.5f)) / 0.08f) *
                        exp(-((b - 0.5f) * (b - 0.5f)) / 0.08f)
                    out[i] = (contrast + 0.02f) * (saturation + 0.05f) * (well + 1e-4f)
                }
            }
            return out
        }

        /** Blur with a 5 tap filter and keep every second pixel. */
        fun reduce(src: FloatArray, w: Int, h: Int): FloatArray {
            val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
            val tmp = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0f
                    for (j in -2..2) s += k[j + 2] * src[y * w + (x + j).coerceIn(0, w - 1)]
                    tmp[y * w + x] = s / 16f
                }
            }
            val dw = (w + 1) / 2
            val dh = (h + 1) / 2
            val out = FloatArray(dw * dh)
            for (y in 0 until dh) {
                for (x in 0 until dw) {
                    var s = 0f
                    for (j in -2..2) s += k[j + 2] * tmp[((2 * y + j).coerceIn(0, h - 1)) * w + min(2 * x, w - 1)]
                    out[y * dw + x] = s / 16f
                }
            }
            return out
        }

        /** Smoothly enlarge to [dw] by [dh]. */
        fun expand(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
            val out = FloatArray(dw * dh)
            for (y in 0 until dh) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = min(fy.toInt(), max(sh - 2, 0))
                val ty = if (sh > 1) fy - y0 else 0f
                val y1 = min(y0 + 1, sh - 1)
                for (x in 0 until dw) {
                    val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = min(fx.toInt(), max(sw - 2, 0))
                    val tx = if (sw > 1) fx - x0 else 0f
                    val x1 = min(x0 + 1, sw - 1)
                    out[y * dw + x] = (src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx) * (1 - ty) +
                        (src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx) * ty
                }
            }
            return out
        }
    }
}
