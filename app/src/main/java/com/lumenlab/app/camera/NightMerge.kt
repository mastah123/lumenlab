package com.lumenlab.app.camera

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Handheld night photo: many short photos are lined up and averaged. Short photos are sharp when held by hand, and
 * averaging them removes the noise that the high ISO adds. A part that moved between photos is taken from the
 * reference photo only, so people and leaves do not ghost.
 */
object NightMerge {
    /** How each photo is taken. [gain] brightens the result when the shorter time could not be made up with ISO. */
    class Plan(val exposureNs: Long, val iso: Int, val gain: Float)

    fun plan(
        baseExposureNs: Long,
        baseIso: Int,
        capNs: Long,
        minExposureNs: Long,
        maxExposureNs: Long,
        minIso: Int,
        maxIso: Int,
    ): Plan {
        val exposure = min(baseExposureNs, capNs).coerceIn(minExposureNs, maxExposureNs)
        val iso = (baseIso.toDouble() * baseExposureNs / exposure).toInt().coerceIn(minIso, maxIso)
        val achieved = (exposure.toDouble() * iso) / (baseExposureNs.toDouble() * baseIso)
        val gain = if (achieved < 1.0) (1.0 / achieved).toFloat().coerceIn(1f, 4f) else 1f
        return Plan(exposure, iso, gain)
    }

    /** Index of the sharpest photo, which the others are lined up to. */
    fun reference(sharpness: List<Double>): Int = sharpness.indices.maxByOrNull { sharpness[it] } ?: 0

    /** Photos clearly blurrier than the best one (hand shake during the exposure) are left out. */
    fun keep(sharpness: List<Double>, ratio: Double = 0.6): List<Boolean> {
        val best = sharpness.maxOrNull() ?: return emptyList()
        return sharpness.map { it >= best * ratio }
    }

    /** Running average of lined-up photos around a reference. */
    class Accumulator(val w: Int, val h: Int, private val sigma: Float = 28f) {
        private val sum = FloatArray(w * h * 3)
        private val weight = FloatArray(w * h)
        private var ref: IntArray? = null
        var count = 0
            private set

        fun setReference(px: IntArray) {
            ref = px.copyOf()
            for (i in 0 until w * h) {
                val p = px[i]
                sum[i * 3] += ((p shr 16) and 0xFF).toFloat()
                sum[i * 3 + 1] += ((p shr 8) and 0xFF).toFloat()
                sum[i * 3 + 2] += (p and 0xFF).toFloat()
                weight[i] += 1f
            }
            count++
        }

        /** Adds a lined-up photo. Pixels that differ much from the reference count for little. */
        fun add(px: IntArray) {
            val r = ref ?: return setReference(px)
            val inv = 1f / (sigma * sigma)
            for (i in 0 until w * h) {
                val p = px[i]
                val q = r[i]
                val dr = ((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)
                val dg = ((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)
                val db = (p and 0xFF) - (q and 0xFF)
                val d2 = (dr * dr + dg * dg + db * db) / 3f
                val wt = exp(-d2 * inv)
                sum[i * 3] += wt * ((p shr 16) and 0xFF)
                sum[i * 3 + 1] += wt * ((p shr 8) and 0xFF)
                sum[i * 3 + 2] += wt * (p and 0xFF)
                weight[i] += wt
            }
            count++
        }

        /** The average, brightened by [gain] (a light amount, applied as a gamma-aware multiplier). */
        fun result(gain: Float = 1f): IntArray {
            val k = gain.toDouble().pow(1 / 2.2).toFloat()
            return IntArray(w * h) { i ->
                val wt = max(weight[i], 1e-6f)
                val r = (sum[i * 3] / wt * k + 0.5f).toInt().coerceIn(0, 255)
                val g = (sum[i * 3 + 1] / wt * k + 0.5f).toInt().coerceIn(0, 255)
                val b = (sum[i * 3 + 2] / wt * k + 0.5f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
