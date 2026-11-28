package com.lumenlab.app.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseTest {
    @Test
    fun planNumbers() {
        assertEquals(360, LapsePlan.frames(3600, 10))
        assertEquals(1, LapsePlan.frames(3, 10))
        assertEquals(3600, LapsePlan.frames(86400, 2))
        assertEquals(12.0, LapsePlan.clipSeconds(360, 30), 1e-9)
        assertEquals(300, LapsePlan.speedUp(10, 30))
        assertEquals("12 s", LapsePlan.durationText(12.0))
        assertEquals("1 min 15 s", LapsePlan.durationText(75.0))
    }

    @Test
    fun videoSizeKeepsShapeAndIsMultipleOf16() {
        val (w, h) = LapsePlan.videoSize(4000, 3000, 1920)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue(w <= 1920 && h <= 1920)
        assertEquals(4.0 / 3.0, w.toDouble() / h, 0.03)
        val (pw, ph) = LapsePlan.videoSize(3000, 4000, 1280)
        assertTrue(ph > pw && ph <= 1280)
        val (sw, sh) = LapsePlan.videoSize(640, 480, 1920)
        assertEquals(640, sw)
        assertEquals(480, sh)
    }

    @Test
    fun bitrateWithinLimits() {
        assertEquals(12_441_600, LapsePlan.bitrate(1920, 1080, 30))
        assertTrue(LapsePlan.bitrate(1280, 720, 24) in 2_000_000..20_000_000)
        assertEquals(2_000_000, LapsePlan.bitrate(160, 96, 24))
    }

    @Test
    fun deflickerRemovesJumpsButKeepsSlowChange() {
        val flicker = List(40) { if (it % 2 == 0) 100.0 else 120.0 }
        val g = Deflicker.gains(flicker)
        val fixed = flicker.indices.map { flicker[it] * g[it] }
        val spreadBefore = flicker.max() - flicker.min()
        val spreadAfter = fixed.subList(6, 34).max() - fixed.subList(6, 34).min()
        assertTrue("before $spreadBefore after $spreadAfter", spreadAfter < spreadBefore * 0.3)
        val ramp = List(60) { 50.0 + it * 2.0 }
        val gr = Deflicker.gains(ramp)
        for (i in 6 until 54) assertEquals(1.0, gr[i], 0.01)
        assertTrue(Deflicker.gains(listOf(0.0, 0.5)).all { it == 1.0 })
    }

    @Test
    fun deflickerApplyScalesBrightness() {
        val px = IntArray(100) { 0xFF404040.toInt() }
        Deflicker.apply(px, 1.5)
        assertEquals(0x60, px[0] and 0xFF)
        val same = IntArray(10) { 0xFF404040.toInt() }
        Deflicker.apply(same, 1.001)
        assertEquals(0x40, same[0] and 0xFF)
        assertEquals(128.0, Deflicker.meanLuma(IntArray(50) { 0xFF808080.toInt() }), 0.5)
    }

    @Test
    fun yuvOfKnownColours() {
        fun yuv(color: Int): Triple<Int, Int, Int> {
            val p = YuvConvert.convert(IntArray(16) { color }, 4, 4)
            return Triple(p.y[0].toInt() and 0xFF, p.u[0].toInt() and 0xFF, p.v[0].toInt() and 0xFF)
        }
        val white = yuv(0xFFFFFFFF.toInt())
        assertEquals(235, white.first)
        assertEquals(128, white.second)
        assertEquals(128, white.third)
        assertEquals(16, yuv(0xFF000000.toInt()).first)
        val red = yuv(0xFFFF0000.toInt())
        assertEquals(81, red.first)
        assertEquals(90, red.second)
        assertEquals(240, red.third)
        val blue = yuv(0xFF0000FF.toInt())
        assertEquals(41, blue.first)
        assertEquals(240, blue.second)
        assertEquals(110, blue.third)
        val p = YuvConvert.convert(IntArray(16 * 8), 16, 8)
        assertEquals(128, p.y.size)
        assertEquals(32, p.u.size)
    }

    @Test
    fun writePlaneHonoursStrides() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val tight = ByteBuffer.allocate(6)
        YuvConvert.writePlane(src, 3, 2, tight, 3, 1)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), tight.array().toList())
        val padded = ByteBuffer.allocate(12)
        YuvConvert.writePlane(src, 3, 2, padded, 6, 2)
        assertEquals(1.toByte(), padded.get(0))
        assertEquals(2.toByte(), padded.get(2))
        assertEquals(3.toByte(), padded.get(4))
        assertEquals(4.toByte(), padded.get(6))
        assertEquals(0.toByte(), padded.get(1))
        assertEquals(6.toByte(), padded.get(10))
    }
}
