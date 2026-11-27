package com.lumenlab.app.camera

import java.time.Instant
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkyTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun azimuthAtTransitIsSouthAndEastRisesInTheEast() {
        val body = SkyInfo.Body(0.0, 0.0, 0.0)
        // Pick a time when the body is on the meridian: local sidereal time equals its right ascension.
        var t = ms("2024-06-01T00:00:00Z")
        val lat = 10.0
        val lon = 0.0
        var best = Double.MAX_VALUE
        var bestT = t
        while (t < ms("2024-06-02T00:00:00Z")) {
            val alt = SkyInfo.altitude(body, SkyInfo.julianDay(t), lat, lon)
            if (90 - alt < best) { best = 90 - alt; bestT = t }
            t += 60_000L
        }
        assertEquals(180.0, SkyInfo.azimuth(body, SkyInfo.julianDay(bestT), lat, lon), 1.0)
        // Six hours earlier it is low in the east.
        val early = bestT - 6 * 3_600_000L
        val az = SkyInfo.azimuth(body, SkyInfo.julianDay(early), lat, lon)
        assertEquals(90.0, az, 6.0)
    }

    @Test
    fun coreIsHighOnJulyNightsAndAbsentInDecember() {
        // Manila, mid July: the core crosses the south at about 46 degrees.
        val july = MilkyWay.nextWindow(ms("2026-07-15T08:00:00Z"), 14.6, 121.0)
        assertNotNull(july)
        assertTrue("peak ${july!!.peakAltitude}", july.peakAltitude in 40.0..47.5)
        assertTrue("az ${july.peakAzimuth}", Math.abs(july.peakAzimuth - 180.0) < 25.0)
        assertTrue(july.endMs > july.startMs)
        // In December the core is next to the Sun, so there is no dark window with it up.
        assertNull(MilkyWay.nextWindow(ms("2026-12-20T08:00:00Z"), 14.6, 121.0, hours = 24))
    }

    @Test
    fun compassNames() {
        assertEquals("N", MilkyWay.compass(359.0))
        assertEquals("N", MilkyWay.compass(5.0))
        assertEquals("SE", MilkyWay.compass(135.0))
        assertEquals("W", MilkyWay.compass(268.0))
    }

    @Test
    fun skyShareFollowsTheHorizon() {
        assertEquals(1f, MilkyBlend.skyShare(10, 100, 0.6f, 0f), 0f)
        assertEquals(0f, MilkyBlend.skyShare(90, 100, 0.6f, 0f), 0f)
        assertEquals(1f, MilkyBlend.skyShare(10, 100, 0.6f, 0.05f), 1e-4f)
        assertEquals(0f, MilkyBlend.skyShare(90, 100, 0.6f, 0.05f), 1e-4f)
        val mid = MilkyBlend.skyShare(60, 100, 0.6f, 0.05f)
        assertTrue("mid $mid", mid in 0.3f..0.7f)
    }

    @Test
    fun blendUsesSkyAboveAndGroundBelow() {
        val w = 8
        val h = 20
        val sky = IntArray(w * h) { 0xFF0000FF.toInt() }
        val ground = IntArray(w * h) { 0xFF00FF00.toInt() }
        val out = MilkyBlend.blend(sky, ground, w, h, 0.5f, 0.1f)
        assertEquals(0xFF0000FF.toInt(), out[0])
        assertEquals(0xFF00FF00.toInt(), out[(h - 1) * w])
        val seam = out[10 * w]
        assertTrue((seam and 0xFF) in 1..254 && ((seam shr 8) and 0xFF) in 1..254)
    }

    @Test
    fun toneSetsBlackPointLiftsMidsAndAddsColour() {
        val rnd = Random(4)
        val px = IntArray(4000) {
            val base = 20 + rnd.nextInt(6)
            val v = if (it % 10 == 0) 120 else base
            (0xFF shl 24) or ((v + 10) shl 16) or (v shl 8) or v
        }
        val before = px.copyOf()
        MilkyBlend.tone(px, 1f)
        var darkBefore = 0.0
        var darkAfter = 0.0
        var n = 0
        for (i in px.indices) if (i % 10 != 0) { darkBefore += before[i] and 0xFF; darkAfter += px[i] and 0xFF; n++ }
        assertTrue("dark ${darkBefore / n} -> ${darkAfter / n}", darkAfter / n < 15 && darkAfter < darkBefore)
        val midBefore = before[0] and 0xFF
        val midAfter = px[0] and 0xFF
        assertTrue("mid $midBefore -> $midAfter", midAfter > midBefore)
        val spreadBefore = ((before[0] shr 16) and 0xFF) - (before[0] and 0xFF)
        // The red gets stronger than blue by more than before the stretch's scaling alone would give.
        assertTrue(((px[0] shr 16) and 0xFF) >= (px[0] and 0xFF) + spreadBefore - 2)
        val untouched = IntArray(100) { 0xFF404040.toInt() }
        MilkyBlend.tone(untouched, 0f)
        assertTrue((untouched[0] and 0xFF) in 0..0x40)
    }

    @Test
    fun horizonIsFoundWhereBrightnessJumps() {
        val w = 60
        val h = 200
        val rnd = Random(8)
        val px = IntArray(w * h) { i ->
            val y = i / w
            val v = (if (y < 130) 90 else 20) + rnd.nextInt(6)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertEquals(0.65f, MilkyBlend.guessHorizon(px, w, h), 0.03f)
    }

    @Test
    fun downscaleAverages() {
        val px = IntArray(16) { if (it % 4 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val (out, w, h) = MilkyBlend.downscale(px, 4, 4, 2)
        assertEquals(2, w)
        assertEquals(2, h)
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFFFFFFFF.toInt(), out[1])
    }
}
