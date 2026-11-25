package com.lumenlab.app.camera

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightMergeTest {
    private val w = 64
    private val h = 48

    private fun scene() = IntArray(w * h) { i ->
        val v = 40 + (i % w) * 2
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun noisy(base: IntArray, rnd: Random, amount: Double) = IntArray(base.size) { i ->
        fun ch(shift: Int) = (((base[i] shr shift) and 0xFF) + rnd.nextGaussian() * amount).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun error(a: IntArray, b: IntArray): Double {
        var e = 0.0
        for (i in a.indices) e += kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF))
        return e / a.size
    }

    @Test
    fun averagingRemovesNoise() {
        val truth = scene()
        val rnd = Random(5)
        val frames = List(12) { noisy(truth, rnd, 14.0) }
        val acc = NightMerge.Accumulator(w, h)
        acc.setReference(frames[0])
        for (i in 1 until frames.size) acc.add(frames[i])
        assertEquals(12, acc.count)
        val single = error(frames[0], truth)
        val merged = error(acc.result(), truth)
        assertTrue("single $single merged $merged", merged < single * 0.55)
    }

    @Test
    fun movedPartsComeFromTheReferenceOnly() {
        val truth = scene()
        val acc = NightMerge.Accumulator(w, h)
        acc.setReference(truth)
        repeat(5) {
            val moved = truth.copyOf()
            for (y in 10..20) for (x in 10..20) moved[y * w + x] = 0xFFFFFFFF.toInt()
            acc.add(moved)
        }
        val out = acc.result()
        assertTrue(error(out, truth) < 1.5)
        assertEquals((truth[15 * w + 15] and 0xFF).toDouble(), (out[15 * w + 15] and 0xFF).toDouble(), 6.0)
    }

    @Test
    fun gainBrightens() {
        val flat = IntArray(w * h) { 0xFF404040.toInt() }
        val acc = NightMerge.Accumulator(w, h)
        acc.setReference(flat)
        val plain = acc.result(1f)[0] and 0xFF
        val bright = acc.result(2f)[0] and 0xFF
        assertEquals(0x40, plain)
        assertEquals(0x40 * Math.pow(2.0, 1 / 2.2), bright.toDouble(), 1.5)
    }

    @Test
    fun planShortensTimeAndRaisesIso() {
        val p = NightMerge.plan(200_000_000L, 400, 66_000_000L, 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(66_000_000L, p.exposureNs)
        assertEquals(1212.0, p.iso.toDouble(), 2.0)
        assertEquals(1f, p.gain, 0.02f)
    }

    @Test
    fun planUsesGainWhenIsoRunsOut() {
        val p = NightMerge.plan(400_000_000L, 1600, 66_000_000L, 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(3200, p.iso)
        assertTrue(p.gain > 2.5f && p.gain <= 4f)
    }

    @Test
    fun planKeepsBrightSceneAlone() {
        val p = NightMerge.plan(10_000_000L, 100, 66_000_000L, 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(10_000_000L, p.exposureNs)
        assertEquals(100, p.iso)
        assertEquals(1f, p.gain, 0f)
    }

    @Test
    fun referenceAndKeep() {
        val s = listOf(10.0, 14.0, 5.0, 13.0)
        assertEquals(1, NightMerge.reference(s))
        assertEquals(listOf(true, true, false, true), NightMerge.keep(s))
    }
}
