package com.lumenlab.app.camera

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyInfoTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun minutesBetween(a: Long, b: Long) = Math.abs(a - b) / 60_000.0

    @Test
    fun sunriseAndSunsetInLondonAtTheEquinox() {
        val lat = 51.4779
        val lon = 0.0
        val c = SkyInfo.crossings(ms("2024-03-20T00:00:00Z"), ms("2024-03-21T00:00:00Z"), SkyInfo.SUN_HORIZON) {
            SkyInfo.sunAltitude(it, lat, lon)
        }
        assertEquals(2, c.size)
        val rise = c.first { it.rising }.timeMs
        val set = c.first { !it.rising }.timeMs
        assertTrue("rise off by ${minutesBetween(rise, ms("2024-03-20T06:03:00Z"))} min", minutesBetween(rise, ms("2024-03-20T06:03:00Z")) < 6)
        assertTrue("set off by ${minutesBetween(set, ms("2024-03-20T18:15:00Z"))} min", minutesBetween(set, ms("2024-03-20T18:15:00Z")) < 6)
    }

    @Test
    fun manilaSunriseIsAroundSix() {
        // Manila is UTC+8; sunrise in late November is a little after 06:00 local time.
        val c = SkyInfo.crossings(ms("2026-11-25T16:00:00Z"), ms("2026-11-26T16:00:00Z"), SkyInfo.SUN_HORIZON) {
            SkyInfo.sunAltitude(it, 14.6, 121.0)
        }
        val rise = c.first { it.rising }.timeMs
        val local = Instant.ofEpochMilli(rise).atZone(java.time.ZoneOffset.ofHours(8))
        val minutes = local.hour * 60 + local.minute
        assertTrue("minutes $minutes", minutes in (6 * 60) .. (6 * 60 + 20))
    }

    @Test
    fun newAndFullMoonTimes() {
        val newMoon = SkyInfo.nextElongation(0.0, ms("2024-04-01T00:00:00Z"))
        assertTrue("new ${Instant.ofEpochMilli(newMoon)}", minutesBetween(newMoon, ms("2024-04-08T18:21:00Z")) < 120)
        val fullMoon = SkyInfo.nextElongation(180.0, ms("2024-04-10T00:00:00Z"))
        assertTrue("full ${Instant.ofEpochMilli(fullMoon)}", minutesBetween(fullMoon, ms("2024-04-23T23:49:00Z")) < 120)
    }

    @Test
    fun phaseAndIllumination() {
        val atNew = SkyInfo.phase(ms("2024-04-08T18:21:00Z"))
        assertEquals("New moon", atNew.name)
        assertTrue(atNew.illumination < 0.01)
        val atFull = SkyInfo.phase(ms("2024-04-23T23:49:00Z"))
        assertEquals("Full moon", atFull.name)
        assertTrue(atFull.illumination > 0.99)
        val quarter = SkyInfo.phase(ms("2024-04-15T19:13:00Z"))
        assertEquals("First quarter", quarter.name)
        assertEquals(0.5, quarter.illumination, 0.05)
        assertTrue(quarter.waxing)
        assertTrue(SkyInfo.phase(ms("2024-04-28T00:00:00Z")).let { !it.waxing && it.name == "Waning gibbous" })
    }

    @Test
    fun moonRisesAboutOncePerDayAndLaterEachDay() {
        val lat = 40.0
        val lon = -100.0
        fun rise(from: String): Long {
            val s = ms(from)
            return SkyInfo.crossings(s, s + 26 * 3_600_000L, 0.0) { SkyInfo.moonAltitude(it, lat, lon) - SkyInfo.moonHorizon(it) }
                .first { it.rising }.timeMs
        }
        val a = rise("2024-04-10T00:00:00Z")
        val b = rise("2024-04-11T00:00:00Z")
        val gap = (b - a) / 60_000.0
        assertTrue("gap $gap", gap in 24 * 60.0 + 20 .. 24 * 60.0 + 80)
    }

    @Test
    fun moonlessDarkTimeIsInsideAstronomicalDark() {
        val lat = 14.6
        val lon = 121.0
        val start = ms("2026-11-26T08:00:00Z")
        val end = start + 24 * 3_600_000L
        val dark = SkyInfo.astronomicalDark(start, end, lat, lon)
        val moonless = SkyInfo.moonlessDark(start, end, lat, lon)
        assertEquals(1, dark.size)
        val night = dark[0].minutes
        assertTrue("night $night", night in 8 * 60L .. 12 * 60L)
        for (s in moonless) assertTrue(s.startMs >= dark[0].startMs - 300_000L && s.endMs <= dark[0].endMs + 300_000L)
        assertTrue(moonless.sumOf { it.minutes } <= night)
    }

    @Test
    fun adviceChangesWithBrightness() {
        assertTrue(SkyInfo.moonAdvice(0.02).contains("best"))
        assertTrue(SkyInfo.moonAdvice(0.95).contains("very bright"))
        assertEquals(10.0, SkyInfo.angleDiff(355.0, 5.0), 1e-9)
    }
}
