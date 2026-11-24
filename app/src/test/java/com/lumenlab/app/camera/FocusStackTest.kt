package com.lumenlab.app.camera

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStackTest {
    private val w = 96
    private val h = 64

    private fun texture(): IntArray {
        val rnd = Random(7)
        return IntArray(w * h) {
            val v = 40 + rnd.nextInt(180)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun blurred(src: IntArray): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var s = 0
        var n = 0
        for (j in -2..2) for (k in -2..2) {
            val xx = (x + k).coerceIn(0, w - 1)
            val yy = (y + j).coerceIn(0, h - 1)
            s += src[yy * w + xx] and 0xFF
            n++
        }
        val v = s / n
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Left half from one picture, right half from the other. */
    private fun halves(left: IntArray, right: IntArray) = IntArray(w * h) { i -> if (i % w < w / 2) left[i] else right[i] }

    private fun error(a: IntArray, truth: IntArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs((a[i] and 0xFF) - (truth[i] and 0xFF))
        return s / a.size
    }

    @Test
    fun stepsRunFromNearToFar() {
        val steps = FocusStack.focusSteps(10f, 0f, 5)
        assertEquals(listOf(10f, 7.5f, 5f, 2.5f, 0f), steps)
        assertEquals(10f, FocusStack.focusSteps(10f, 0.25f, 4).first(), 1e-6f)
        assertEquals(2.5f, FocusStack.focusSteps(10f, 0.25f, 4).last(), 1e-6f)
        assertEquals(listOf(0f), FocusStack.focusSteps(0f, 0f, 5))
    }

    @Test
    fun mergeTakesTheSharpPartOfEachPhoto() {
        val sharp = texture()
        val soft = blurred(sharp)
        val a = halves(sharp, soft)
        val b = halves(soft, sharp)
        val acc = FocusStack.Accumulator(w, h)
        acc.add(a)
        acc.add(b)
        val merged = acc.result()
        val mergedError = error(merged, sharp)
        assertEquals(2, acc.count)
        assertTrue("merged=$mergedError a=${error(a, sharp)}", mergedError < error(a, sharp) * 0.35)
        assertTrue("merged=$mergedError b=${error(b, sharp)}", mergedError < error(b, sharp) * 0.35)
    }

    @Test
    fun flatPhotosStayFlat() {
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        val acc = FocusStack.Accumulator(w, h)
        acc.add(flat)
        acc.add(flat.copyOf())
        assertTrue(acc.result().all { it == 0xFF336699.toInt() })
    }

    @Test
    fun warpMovesThePicture() {
        val src = IntArray(w * h) { val v = (it % w) * 2; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val moved = FocusStack.warp(src, w, h, 3f, 0f)
        assertEquals(((10 + 3) * 2), moved[5 * w + 10] and 0xFF)
        assertEquals(src[5 * w + 20], FocusStack.warp(src, w, h, 0f, 0f)[5 * w + 20])
    }

    @Test
    fun measureIsHigherOnDetail() {
        val sharp = SuperRes.luma(texture())
        val flat = FloatArray(w * h) { 100f }
        val m1 = FocusStack.measure(sharp, w, h)
        val m2 = FocusStack.measure(flat, w, h)
        assertTrue(m1[30 * w + 40] > 20f)
        assertEquals(0f, m2[30 * w + 40], 1e-4f)
    }
}
