package com.lumenlab.app.camera

import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStackTest {
    private fun frame(values: IntArray) = ShortBuffer.wrap(ShortArray(values.size) { values[it].toShort() })
    private fun v(s: Short) = s.toInt() and 0xFFFF

    @Test
    fun averagesFewFrames() {
        val s = RawStack(2, 1)
        s.add(frame(intArrayOf(100, 200)))
        s.add(frame(intArrayOf(110, 210)))
        s.add(frame(intArrayOf(120, 220)))
        assertEquals(3, s.count)
        val r = s.result()
        assertEquals(110, v(r[0]))
        assertEquals(210, v(r[1]))
    }

    @Test
    fun dropsOutliersFromFourFrames() {
        val s = RawStack(1, 1)
        for (x in intArrayOf(100, 102, 98, 60000)) s.add(frame(intArrayOf(x)))
        assertEquals(101, v(s.result()[0]))
        val t = RawStack(1, 1)
        for (x in intArrayOf(100, 100, 100, 0, 100)) t.add(frame(intArrayOf(x)))
        assertEquals(100, v(t.result()[0]))
    }

    @Test
    fun handlesValuesAboveSignedRange() {
        val s = RawStack(1, 1)
        for (x in intArrayOf(65000, 65100, 65200)) s.add(frame(intArrayOf(x)))
        assertEquals(65100, v(s.result()[0]))
    }

    @Test
    fun readsRowStride() {
        val s = RawStack(2, 2)
        s.add(frame(intArrayOf(1, 2, 999, 3, 4, 999)), rowStride = 3)
        assertEquals(listOf(1, 2, 3, 4), s.result().map { v(it) })
    }

    @Test
    fun noiseFallsWithMoreFrames() {
        val rnd = Random(5)
        val n = 4000
        fun sigma(frames: Int): Double {
            val s = RawStack(n, 1)
            repeat(frames) { s.add(frame(IntArray(n) { (2000 + rnd.nextGaussian() * 40).toInt() })) }
            val r = s.result().map { v(it).toDouble() }
            val mean = r.average()
            return sqrt(r.sumOf { (it - mean) * (it - mean) } / n)
        }
        val one = sigma(1)
        val sixteen = sigma(16)
        assertTrue("one $one sixteen $sixteen", sixteen < one * 0.35)
    }

    @Test
    fun emptyStackIsBlank() {
        assertTrue(RawStack(3, 3).result().all { it == 0.toShort() })
    }

    @Test
    fun bytesAreLittleEndian() {
        val b = RawStack.toBytes(shortArrayOf(0x1234, 0xABCD.toShort()))
        assertEquals(0x34, b.get(0).toInt() and 0xFF)
        assertEquals(0x12, b.get(1).toInt() and 0xFF)
        assertEquals(0xCD, b.get(2).toInt() and 0xFF)
        assertEquals(0xAB, b.get(3).toInt() and 0xFF)
    }

    @Test
    fun orientationAndMemory() {
        assertEquals(6, RawStack.exifOrientation(90))
        assertEquals(3, RawStack.exifOrientation(180))
        assertEquals(8, RawStack.exifOrientation(270))
        assertEquals(1, RawStack.exifOrientation(0))
        assertEquals(8, RawStack.exifOrientation(-90))
        assertEquals(120_000_000L, RawStack.bytesNeeded(4000, 3000))
        assertTrue(RawStack.fits(4000, 3000, 200_000_000L))
        assertTrue(!RawStack.fits(8000, 6000, 200_000_000L))
        assertEquals(10.0, RawStack.shootSeconds(5, 1_500_000_000L), 1e-9)
    }
}
