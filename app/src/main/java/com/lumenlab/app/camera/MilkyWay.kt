package com.lumenlab.app.camera

import kotlin.math.max
import kotlin.math.min

/** When and where the bright core of the Milky Way (towards the middle of the galaxy) is in the sky. */
object MilkyWay {
    /** The galactic centre, J2000. Precession since then is a third of a degree, far less than needed here. */
    const val CORE_RA = 266.417
    const val CORE_DEC = -29.008

    /** Lowest height worth shooting, in degrees. */
    const val MIN_ALTITUDE = 10.0

    private val core = SkyInfo.Body(CORE_RA, CORE_DEC, 0.0)

    fun altitude(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = SkyInfo.julianDay(epochMs)
        return SkyInfo.altitude(core, jd, lat, lon)
    }

    fun azimuth(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = SkyInfo.julianDay(epochMs)
        return SkyInfo.azimuth(core, jd, lat, lon)
    }

    /** A stretch of dark sky with the core above [MIN_ALTITUDE]. [moonless] is true when the Moon is also down for all of it. */
    class Window(
        val startMs: Long,
        val endMs: Long,
        val peakMs: Long,
        val peakAltitude: Double,
        val peakAzimuth: Double,
        val moonless: Boolean,
    )

    /**
     * The first window within [hours] of [fromMs] when the Sun is below -18° and the core is high enough.
     * Returns null when there is none, which is normal in the months when the core is near the Sun.
     */
    fun nextWindow(fromMs: Long, lat: Double, lon: Double, hours: Int = 36, stepMs: Long = 300_000L): Window? {
        val end = fromMs + hours * 3_600_000L
        var start = -1L
        var peakT = 0L
        var peakAlt = -90.0
        var moonUp = false
        var t = fromMs
        while (t <= end + stepMs) {
            val good = t <= end &&
                SkyInfo.sunAltitude(t, lat, lon) < SkyInfo.ASTRONOMICAL && altitude(t, lat, lon) >= MIN_ALTITUDE
            if (good) {
                if (start < 0) { start = t; peakAlt = -90.0; moonUp = false }
                val a = altitude(t, lat, lon)
                if (a > peakAlt) { peakAlt = a; peakT = t }
                if (SkyInfo.moonAltitude(t, lat, lon) >= SkyInfo.moonHorizon(t)) moonUp = true
            } else if (start >= 0) {
                return Window(start, t, peakT, peakAlt, azimuth(peakT, lat, lon), !moonUp)
            }
            t += stepMs
        }
        return null
    }

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass(azimuthDeg: Double): String = POINTS[((azimuthDeg + 22.5) / 45.0).toInt() % 8]

    fun clampAltitude(a: Double): Int = max(0.0, min(90.0, a)).toInt()
}
