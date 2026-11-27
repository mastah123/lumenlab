package com.lumenlab.app.camera

import kotlin.math.max
import kotlin.math.min

/**
 * A flat field: how much each part of the picture is darkened by the lens (vignetting) and by dust, worked out from a
 * photo of an evenly lit surface. Dividing a light frame by it brightens the corners and removes dust shadows.
 * It is kept at a quarter of the size, which is plenty for vignetting and still shows dust rings, and enlarged smoothly.
 */
class FlatField(
    val lowWidth: Int,
    val lowHeight: Int,
    val factor: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    private val gain: FloatArray,
) {
    fun matches(width: Int, height: Int): Boolean = width == fullWidth && height == fullHeight

    /** Multiplies every pixel by its gain. Works in place; does nothing if the size is not the one the flat was made for. */
    fun apply(px: IntArray, width: Int, height: Int) {
        if (!matches(width, height)) return
        val xs = IntArray(width)
        val xt = FloatArray(width)
        for (x in 0 until width) {
            val f = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lowWidth - 1).toFloat())
            val x0 = min(f.toInt(), max(lowWidth - 2, 0))
            xs[x] = x0
            xt[x] = if (lowWidth > 1) f - x0 else 0f
        }
        for (y in 0 until height) {
            val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lowHeight - 1).toFloat())
            val y0 = min(fy.toInt(), max(lowHeight - 2, 0))
            val ty = if (lowHeight > 1) fy - y0 else 0f
            val y1 = min(y0 + 1, lowHeight - 1)
            for (x in 0 until width) {
                val x0 = xs[x]
                val x1 = min(x0 + 1, lowWidth - 1)
                val tx = xt[x]
                val i00 = (y0 * lowWidth + x0) * 3
                val i10 = (y0 * lowWidth + x1) * 3
                val i01 = (y1 * lowWidth + x0) * 3
                val i11 = (y1 * lowWidth + x1) * 3
                val p = px[y * width + x]
                var out = 0xFF shl 24
                for (c in 0 until 3) {
                    val g = (gain[i00 + c] * (1 - tx) + gain[i10 + c] * tx) * (1 - ty) +
                        (gain[i01 + c] * (1 - tx) + gain[i11 + c] * tx) * ty
                    val shift = 16 - 8 * c
                    val v = (((p shr shift) and 0xFF) * g + 0.5f).toInt().coerceIn(0, 255)
                    out = out or (v shl shift)
                }
                px[y * width + x] = out
            }
        }
    }

    companion object {
        const val FACTOR = 4

        /** Brightest correction allowed, so a very dark corner or a dead patch is not blown up. */
        const val MAX_GAIN = 3f
        const val MIN_GAIN = 0.5f

        /**
         * Builds the field from the average flat picture [low] ([lowWidth] by [lowHeight]). Each colour is measured against its
         * own level in the middle of the picture, so the centre stays as it is and the edges are brought up to it.
         */
        fun fromFlat(low: IntArray, lowWidth: Int, lowHeight: Int, factor: Int, fullWidth: Int, fullHeight: Int): FlatField {
            val level = centreLevels(low, lowWidth, lowHeight)
            val gain = FloatArray(lowWidth * lowHeight * 3)
            for (i in 0 until lowWidth * lowHeight) {
                val p = low[i]
                for (c in 0 until 3) {
                    val v = ((p shr (16 - 8 * c)) and 0xFF).toFloat()
                    val g = level[c] / max(v, level[c] / MAX_GAIN)
                    gain[i * 3 + c] = g.coerceIn(MIN_GAIN, MAX_GAIN)
                }
            }
            return FlatField(lowWidth, lowHeight, factor, fullWidth, fullHeight, gain)
        }

        /** Mean of each colour over the middle 40% of the picture. */
        fun centreLevels(low: IntArray, w: Int, h: Int): FloatArray {
            val x0 = (w * 0.3f).toInt()
            val x1 = max((w * 0.7f).toInt(), x0 + 1)
            val y0 = (h * 0.3f).toInt()
            val y1 = max((h * 0.7f).toInt(), y0 + 1)
            val sum = DoubleArray(3)
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = low[y * w + x]
                sum[0] += (p shr 16) and 0xFF
                sum[1] += (p shr 8) and 0xFF
                sum[2] += p and 0xFF
                n++
            }
            return FloatArray(3) { max((sum[it] / n).toFloat(), 1f) }
        }

        /** A flat that is neither too dark nor too bright to be useful: the middle brightness between 60 and 215. */
        fun exposureOk(low: IntArray, w: Int, h: Int): Boolean {
            val level = centreLevels(low, w, h)
            val luma = level[0] * 0.299f + level[1] * 0.587f + level[2] * 0.114f
            return luma in 60f..215f
        }
    }
}
