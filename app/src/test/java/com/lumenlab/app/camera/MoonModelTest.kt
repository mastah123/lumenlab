package com.lumenlab.app.camera

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonModelTest {
    private val w = 640
    private val h = 480

    /** A dark sky with noise, a lit disk, and a small bright lamp. */
    private fun sky(cx: Int, cy: Int, r: Int, lamp: Boolean = true): FloatArray {
        val rnd = Random(3)
        val a = FloatArray(w * h) { 6f + rnd.nextFloat() * 4f }
        for (y in 0 until h) for (x in 0 until w) {
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d <= r * r) a[y * w + x] = 200f - 60f * d / (r * r)
        }
        if (lamp) for (y in 40..55) for (x in 40..55) a[y * w + x] = 255f
        return a
    }

    @Test
    fun findsTheMoonNotTheLamp() {
        val f = MoonModel.find(sky(300, 200, 60), w, h)
        assertNotNull(f)
        assertEquals(300f, f!!.cx, 1.5f)
        assertEquals(200f, f.cy, 1.5f)
        assertEquals(120f, f.width.toFloat(), 10f)
    }

    @Test
    fun noMoonInAnEmptySky() {
        val rnd = Random(1)
        assertNull(MoonModel.find(FloatArray(w * h) { 5f + rnd.nextFloat() * 3f }, w, h))
    }

    @Test
    fun cropWindowKeepsInsideAndReportsOffset() {
        val f = MoonModel.Found(300.4f, 200.6f, 120, 120)
        val side = MoonModel.cropSide(f, w, h)
        assertEquals(192, side)
        val win = MoonModel.window(f.cx, f.cy, side, w, h)
        assertEquals(300 - 96, win.x0)
        assertEquals(0.4f, win.dx, 0.01f)
        assertEquals(-0.4f, win.dy, 0.01f)
        val edge = MoonModel.window(10f, 10f, side, w, h)
        assertEquals(0, edge.x0)
        assertEquals(10f - 96f, edge.dx, 0.01f)
        assertTrue(MoonModel.cropSide(MoonModel.Found(0f, 0f, 2000, 2000), w, h) <= 480)
    }

    @Test
    fun luckyFramesKeepTheSharpest() {
        val s = listOf(5.0, 9.0, 1.0, 8.0, 7.0, 2.0, 6.0, 3.0, 4.0, 10.0)
        val keep = MoonModel.luckyFrames(s)
        assertEquals(6, keep.size)
        assertEquals(9, keep[0])
        assertTrue(keep.all { s[it] >= 5.0 })
        assertEquals(3, MoonModel.luckyFrames(listOf(1.0, 2.0, 3.0), 0.2f).size)
    }

    @Test
    fun exposureByPhaseAndEv() {
        val full = MoonModel.exposure(MoonModel.PHASES[0], 0f, 100_000L, 1_000_000_000L, 100, 3200)
        assertEquals(1_000_000L, full.first)
        assertEquals(100, full.second)
        val bright = MoonModel.exposure(MoonModel.PHASES[0], 1f, 100_000L, 1_000_000_000L, 100, 3200)
        assertEquals(2_000_000L, bright.first)
        val lowIso = MoonModel.exposure(MoonModel.PHASES[0], 0f, 100_000L, 1_000_000_000L, 50, 3200)
        assertEquals(2_000_000L, lowIso.first)
        assertEquals(50, lowIso.second)
        val clamped = MoonModel.exposure(MoonModel.PHASES[0], -2f, 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(1_000_000L, clamped.first)
    }

    @Test
    fun finishStretchesAndKeepsSizes() {
        val px = IntArray(64 * 64) { i ->
            val v = if (i % 64 < 32) 30 else 120
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        MoonModel.finish(px, 64, 64, 0f)
        val dark = px[5] and 0xFF
        val bright = px[60] and 0xFF
        assertTrue("dark $dark", dark <= 5)
        assertTrue("bright $bright", bright >= 230)
    }
}
