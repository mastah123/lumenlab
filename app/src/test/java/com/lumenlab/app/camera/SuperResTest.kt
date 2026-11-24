package com.lumenlab.app.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResTest {
    // A smooth scene with detail at several sizes. x and y are in low resolution pixel units.
    private fun scene(x: Double, y: Double): Double =
        128 + 60 * sin(2 * PI * (0.07 * x + 0.03 * y)) + 40 * sin(2 * PI * (0.02 * x - 0.09 * y)) +
            25 * sin(2 * PI * (0.31 * x + 0.17 * y))

    private fun frameOf(w: Int, h: Int, sx: Double, sy: Double): FloatArray =
        FloatArray(w * h) { i -> scene(i % w + sx, i / w + sy).toFloat() }

    // Like a real photo, most of the detail is large and only a little is fine, so a quarter size copy still lines up.
    private fun naturalScene(x: Double, y: Double): Double =
        128 + 55 * sin(2 * PI * (0.017 * x + 0.011 * y)) + 40 * sin(2 * PI * (0.009 * x - 0.023 * y)) +
            30 * Math.exp(-((x - 70) * (x - 70) + (y - 50) * (y - 50)) / 180.0) +
            30 * Math.exp(-((x - 110) * (x - 110) + (y - 30) * (y - 30)) / 60.0) +
            8 * sin(2 * PI * (0.11 * x + 0.07 * y))

    private fun naturalFrame(w: Int, h: Int, sx: Double, sy: Double): FloatArray =
        FloatArray(w * h) { i -> naturalScene(i % w + sx, i / w + sy).toFloat() }

    private fun argbOf(luma: FloatArray): IntArray = IntArray(luma.size) {
        val v = luma[it].toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun shiftIsFoundToAFractionOfAPixel() {
        val w = 160
        val h = 120
        val ref = naturalFrame(w, h, 0.0, 0.0)
        for ((sx, sy) in listOf(1.3 to -0.7, -2.6 to 3.4, 0.0 to 0.0, 5.25 to 4.8)) {
            val shift = SuperRes.estimateShift(ref, naturalFrame(w, h, sx, sy), w, h)
            assertEquals("dx for $sx,$sy", -sx, shift.dx.toDouble(), 0.12)
            assertEquals("dy for $sx,$sy", -sy, shift.dy.toDouble(), 0.12)
            assertTrue(SuperRes.usable(shift, w))
        }
    }

    @Test
    fun unrelatedPhotoIsNotUsable() {
        val w = 160
        val h = 120
        val ref = naturalFrame(w, h, 0.0, 0.0)
        val noise = java.util.Random(1)
        val other = FloatArray(w * h) { (noise.nextFloat() * 255f) }
        assertFalse(SuperRes.usable(SuperRes.estimateShift(ref, other, w, h), w))
    }

    @Test
    fun mergeBeatsASingleUpscale() {
        val w = 64
        val h = 48
        val scale = 2
        val offsets = listOf(
            0.0 to 0.0, 0.5 to 0.5, 0.25 to 0.75, 0.75 to 0.25, 0.5 to 0.0, 0.0 to 0.5, 0.35 to 0.15, 0.85 to 0.65,
        )
        val frames = offsets.map { (sx, sy) -> argbOf(frameOf(w, h, sx, sy)) }
        // Positions are given to the merge as the shifts it would have measured.
        val shifts = offsets.map { (sx, sy) -> SuperRes.Shift(-sx.toFloat(), -sy.toFloat(), 0f) }
        val merged = SuperRes.merge(frames, shifts, 0, w, h, scale, sharpen = 0f)
        val single = SuperRes.merge(listOf(frames[0]), listOf(shifts[0]), 0, w, h, scale, sharpen = 0f)
        assertEquals(w * scale * h * scale, merged.size)

        fun mse(img: IntArray): Double {
            var s = 0.0
            var n = 0
            for (y in 8 until h * scale - 8) {
                for (x in 8 until w * scale - 8) {
                    val truth = scene((x + 0.5) / scale - 0.5, (y + 0.5) / scale - 0.5)
                    val d = (img[y * w * scale + x] and 0xFF) - truth
                    s += d * d
                    n++
                }
            }
            return s / n
        }
        val mergedError = mse(merged)
        val singleError = mse(single)
        assertTrue("merged=$mergedError single=$singleError", mergedError < singleError * 0.7)
    }

    @Test
    fun skippedPhotosAreIgnored() {
        val w = 32
        val h = 24
        val a = argbOf(frameOf(w, h, 0.0, 0.0))
        val junk = IntArray(w * h) { 0xFFFF0000.toInt() }
        val out = SuperRes.merge(listOf(a, junk), listOf(SuperRes.Shift(0f, 0f, 0f), null), 0, w, h, 2, sharpen = 0f)
        assertTrue(out.all { ((it shr 16) and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun sharperPhotoScoresHigher() {
        val w = 64
        val h = 48
        val sharp = FloatArray(w * h) { if ((it % w / 4 + it / w / 4) % 2 == 0) 220f else 30f }
        val soft = FloatArray(w * h) { 125f + 10f * sin(it % w / 6.0).toFloat() }
        assertTrue(SuperRes.sharpness(sharp, w, h) > SuperRes.sharpness(soft, w, h))
    }

    @Test
    fun sharpenKeepsFlatAreasAndSizes() {
        val px = IntArray(10 * 10) { 0xFF808080.toInt() }
        SuperRes.sharpen(px, 10, 10, 0.5f)
        assertTrue(px.all { it == 0xFF808080.toInt() })
        assertEquals(0.0, abs(SuperRes.sharpness(FloatArray(100) { 5f }, 10, 10)), 1e-9)
    }
}
