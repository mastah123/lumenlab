package com.lumenlab.app.camera

import java.util.Random
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatFieldTest {
    private val lw = 50
    private val lh = 40
    private val f = 4
    private val fw = lw * f
    private val fh = lh * f

    /** The light the lens lets through: full in the middle, 55% in the corners, and a dust ring near one spot. */
    private fun shading(x: Float, y: Float, dust: Boolean): Float {
        val cx = fw / 2f
        val cy = fh / 2f
        val r = hypot(x - cx, y - cy) / hypot(cx, cy)
        var s = 1f - 0.45f * r * r
        if (dust && hypot(x - 60f, y - 50f) < 12f) s *= 0.8f
        return s
    }

    private fun flatPhoto(dust: Boolean): IntArray = IntArray(lw * lh) { i ->
        val x = (i % lw) * f + f / 2f
        val y = (i / lw) * f + f / 2f
        val v = (180 * shading(x, y, dust)).toInt()
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun lightPhoto(dust: Boolean, level: Int): IntArray = IntArray(fw * fh) { i ->
        val v = (level * shading(i % fw + 0.5f, i / fw + 0.5f, dust)).toInt()
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun vignettingIsFlattened() {
        val field = FlatField.fromFlat(flatPhoto(false), lw, lh, f, fw, fh)
        val px = lightPhoto(false, 100)
        val centreBefore = px[(fh / 2) * fw + fw / 2] and 0xFF
        val cornerBefore = px[5 * fw + 5] and 0xFF
        assertTrue("corner $cornerBefore centre $centreBefore", cornerBefore < centreBefore - 25)
        field.apply(px, fw, fh)
        val corner = px[5 * fw + 5] and 0xFF
        val centre = px[(fh / 2) * fw + fw / 2] and 0xFF
        assertEquals(centre.toDouble(), corner.toDouble(), 6.0)
        assertEquals(100.0, centre.toDouble(), 3.0)
    }

    @Test
    fun dustShadowIsRemoved() {
        val field = FlatField.fromFlat(flatPhoto(true), lw, lh, f, fw, fh)
        val px = lightPhoto(true, 120)
        val nearDustBefore = px[50 * fw + 60] and 0xFF
        val aside = px[50 * fw + 90] and 0xFF
        field.apply(px, fw, fh)
        val nearDust = px[50 * fw + 60] and 0xFF
        val asideAfter = px[50 * fw + 90] and 0xFF
        assertTrue("before $nearDustBefore vs $aside", aside - nearDustBefore > 12)
        assertTrue("after $nearDust vs $asideAfter", Math.abs(asideAfter - nearDust) < 9)
    }

    @Test
    fun colourCastOfTheFlatDoesNotShiftTheCentre() {
        val tinted = IntArray(lw * lh) { (0xFF shl 24) or (200 shl 16) or (150 shl 8) or 100 }
        val field = FlatField.fromFlat(tinted, lw, lh, f, fw, fh)
        val px = IntArray(fw * fh) { (0xFF shl 24) or (80 shl 16) or (90 shl 8) or 100 }
        field.apply(px, fw, fh)
        val p = px[(fh / 2) * fw + fw / 2]
        assertEquals(80, (p shr 16) and 0xFF)
        assertEquals(90, (p shr 8) and 0xFF)
        assertEquals(100, p and 0xFF)
    }

    @Test
    fun gainIsLimitedAndWrongSizeIsIgnored() {
        val dead = IntArray(lw * lh) { (0xFF shl 24) or (180 shl 16) or (180 shl 8) or 180 }
        for (i in 0 until 10) dead[i] = 0xFF000000.toInt()
        val field = FlatField.fromFlat(dead, lw, lh, f, fw, fh)
        val px = IntArray(fw * fh) { 0xFF404040.toInt() }
        field.apply(px, fw, fh)
        assertTrue((px[0] and 0xFF) <= 0x40 * 3 + 1)
        assertTrue(field.matches(fw, fh))
        assertFalse(field.matches(fw + 1, fh))
        val other = IntArray(10 * 10) { 0xFF404040.toInt() }
        field.apply(other, 10, 10)
        assertEquals(0xFF404040.toInt(), other[0])
    }

    @Test
    fun exposureCheck() {
        assertTrue(FlatField.exposureOk(IntArray(lw * lh) { 0xFF808080.toInt() }, lw, lh))
        assertFalse(FlatField.exposureOk(IntArray(lw * lh) { 0xFF101010.toInt() }, lw, lh))
        assertFalse(FlatField.exposureOk(IntArray(lw * lh) { 0xFFF8F8F8.toInt() }, lw, lh))
        val rnd = Random(2)
        assertEquals(128f, FlatField.centreLevels(IntArray(lw * lh) { 0xFF808080.toInt() + rnd.nextInt(1) }, lw, lh)[0], 0.5f)
    }
}
