package com.lumenlab.app.camera

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPaintTest {
    private val w = 80
    private val h = 40

    private fun dark(rnd: Random, noise: Int) = IntArray(w * h) {
        val v = 3 + rnd.nextInt(noise + 1)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun movingLightLeavesATrail() {
        val rnd = Random(2)
        val acc = LightPaint.Accumulator(w, h)
        for (x in 10 until 70) {
            val f = dark(rnd, 4)
            for (dy in -1..1) for (dx in -1..1) f[(20 + dy) * w + x + dx] = 0xFFFFFFFF.toInt()
            acc.add(f)
        }
        assertEquals(60, acc.count)
        val out = acc.result()
        for (x in 10 until 70) assertTrue("x=$x", (out[20 * w + x] and 0xFF) > 240)
        // Away from the trail the sky stays black.
        assertTrue((out[5 * w + 5] and 0xFF) < 10)
    }

    @Test
    fun noiseDoesNotBuildUpIntoHaze() {
        val rnd = Random(9)
        val acc = LightPaint.Accumulator(w, h)
        repeat(200) { acc.add(dark(rnd, 5)) }
        val out = acc.result()
        var maxv = 0
        for (p in out) maxv = maxOf(maxv, p and 0xFF)
        assertTrue("max $maxv", maxv <= 2)
    }

    @Test
    fun brighterWinsPerColour() {
        val acc = LightPaint.Accumulator(w, h, floor = 0)
        acc.add(IntArray(w * h) { 0xFF2040C0.toInt() })
        acc.add(IntArray(w * h) { 0xFFC03020.toInt() })
        val p = acc.result()[0]
        assertEquals(0xC0, (p shr 16) and 0xFF)
        assertEquals(0x40, (p shr 8) and 0xFF)
        assertEquals(0xC0, p and 0xFF)
    }

    @Test
    fun previewIsSmaller() {
        val acc = LightPaint.Accumulator(400, 300)
        val (px, pw, ph) = acc.preview(100)
        assertEquals(100, pw)
        assertEquals(75, ph)
        assertEquals(pw * ph, px.size)
        val (_, sw, sh) = LightPaint.Accumulator(50, 30).preview(100)
        assertEquals(50, sw)
        assertEquals(30, sh)
    }

    @Test
    fun framesAndIso() {
        assertEquals(48, LightPaint.framesFor(60, 1_000_000_000L))
        assertEquals(1, LightPaint.framesFor(0, 1_000_000_000L))
        assertEquals(400, LightPaint.iso(400, 100, 3200))
        assertEquals(800, LightPaint.iso(400, 800, 3200))
    }
}
