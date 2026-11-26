package com.lumenlab.app.camera

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the Sun and Moon are, worked out on the phone with short series formulas (Meeus). The Sun is good to about
 * a minute of time and the Moon to a few minutes, which is plenty for planning a night. All times are epoch milliseconds.
 */
object SkyInfo {
    private const val RAD = Math.PI / 180.0
    private const val MS_PER_DAY = 86_400_000.0
    const val SYNODIC_DAYS = 29.530588

    /** Sun altitudes that mark sunrise or sunset and the three twilights. */
    const val SUN_HORIZON = -0.833
    const val CIVIL = -6.0
    const val NAUTICAL = -12.0
    const val ASTRONOMICAL = -18.0

    fun julianDay(epochMs: Long): Double = epochMs / MS_PER_DAY + 2440587.5

    private fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    /** Right ascension and declination in degrees, plus the ecliptic longitude. */
    class Body(val ra: Double, val dec: Double, val eclipticLon: Double, val parallaxDeg: Double = 0.0)

    private fun toEquatorial(lon: Double, lat: Double, eps: Double): Body {
        val l = lon * RAD
        val b = lat * RAD
        val e = eps * RAD
        val ra = atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l))
        val dec = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l))
        return Body(norm360(ra / RAD), dec / RAD, norm360(lon))
    }

    fun sun(jd: Double): Body {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t
        val m = (357.52911 + 35999.05029 * t) * RAD
        val c = (1.914602 - 0.004817 * t) * sin(m) + 0.019993 * sin(2 * m) + 0.000289 * sin(3 * m)
        val omega = (125.04 - 1934.136 * t) * RAD
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(omega)
        val eps = 23.439291 - 0.0130042 * t + 0.00256 * cos(omega)
        return toEquatorial(lambda, 0.0, eps)
    }

    fun moon(jd: Double): Body {
        val t = (jd - 2451545.0) / 36525.0
        val lp = 218.3164477 + 481267.88123421 * t
        val d = (297.8501921 + 445267.1114034 * t) * RAD
        val m = (357.5291092 + 35999.0502909 * t) * RAD
        val mp = (134.9633964 + 477198.8675055 * t) * RAD
        val f = (93.2720950 + 483202.0175233 * t) * RAD
        val lon = lp + 6.288774 * sin(mp) + 1.274027 * sin(2 * d - mp) + 0.658314 * sin(2 * d) +
            0.213618 * sin(2 * mp) - 0.185116 * sin(m) - 0.114332 * sin(2 * f) + 0.058793 * sin(2 * d - 2 * mp) +
            0.057066 * sin(2 * d - m - mp) + 0.053322 * sin(2 * d + mp) + 0.045758 * sin(2 * d - m) -
            0.040923 * sin(m - mp) - 0.034720 * sin(d) - 0.030383 * sin(m + mp)
        val lat = 5.128122 * sin(f) + 0.280602 * sin(mp + f) + 0.277693 * sin(mp - f) + 0.173237 * sin(2 * d - f) +
            0.055413 * sin(2 * d - mp + f) + 0.046271 * sin(2 * d - mp - f) + 0.032573 * sin(2 * d + f)
        val distanceKm = 385000.56 - 20905.355 * cos(mp) - 3699.111 * cos(2 * d - mp) -
            2955.968 * cos(2 * d) - 569.925 * cos(2 * mp)
        val eps = 23.439291 - 0.0130042 * t
        val body = toEquatorial(lon, lat, eps)
        return Body(body.ra, body.dec, body.eclipticLon, asin(6378.14 / distanceKm) / RAD)
    }

    /** Height above the horizon in degrees, for a place at [latDeg] north and [lonDeg] east. */
    fun altitude(body: Body, jd: Double, latDeg: Double, lonDeg: Double): Double {
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0)
        val hourAngle = (gmst + lonDeg - body.ra) * RAD
        val lat = latDeg * RAD
        val dec = body.dec * RAD
        return asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)) / RAD
    }

    fun sunAltitude(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = julianDay(epochMs)
        return altitude(sun(jd), jd, lat, lon)
    }

    fun moonAltitude(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = julianDay(epochMs)
        return altitude(moon(jd), jd, lat, lon)
    }

    /** Altitude at which the Moon's upper edge touches the horizon, allowing for refraction and parallax. */
    fun moonHorizon(epochMs: Long): Double = 0.7275 * moon(julianDay(epochMs)).parallaxDeg - 0.5667

    /** Angle of the Moon east of the Sun along the ecliptic: 0 new, 90 first quarter, 180 full, 270 last quarter. */
    fun elongation(epochMs: Long): Double {
        val jd = julianDay(epochMs)
        return norm360(moon(jd).eclipticLon - sun(jd).eclipticLon)
    }

    class Phase(val name: String, val emoji: String, val illumination: Double, val ageDays: Double, val waxing: Boolean)

    fun phase(epochMs: Long): Phase {
        val e = elongation(epochMs)
        val k = (1 - cos(e * RAD)) / 2
        val (name, emoji) = when {
            e < 11.25 || e >= 348.75 -> "New moon" to "🌑"
            e < 78.75 -> "Waxing crescent" to "🌒"
            e < 101.25 -> "First quarter" to "🌓"
            e < 168.75 -> "Waxing gibbous" to "🌔"
            e < 191.25 -> "Full moon" to "🌕"
            e < 258.75 -> "Waning gibbous" to "🌖"
            e < 281.25 -> "Last quarter" to "🌗"
            else -> "Waning crescent" to "🌘"
        }
        return Phase(name, emoji, k, e / 360.0 * SYNODIC_DAYS, e < 180.0)
    }

    /** The next time the Moon's elongation reaches [targetDeg] (0 new, 90, 180 full, 270) after [fromMs]. */
    fun nextElongation(targetDeg: Double, fromMs: Long): Long {
        fun f(t: Long): Double = ((elongation(t) - targetDeg + 540.0) % 360.0) - 180.0
        var a = fromMs
        var fa = f(a)
        val step = 3_600_000L
        for (i in 1..(36 * 24)) {
            val b = a + step
            val fb = f(b)
            if (fa < 0 && fb >= 0 && fb - fa < 90.0) return bisect(a, b, ::f)
            a = b
            fa = fb
        }
        return fromMs
    }

    private fun bisect(from: Long, to: Long, f: (Long) -> Double): Long {
        var lo = from
        var hi = to
        val rising = f(lo) < 0
        while (hi - lo > 15_000L) {
            val mid = (lo + hi) / 2
            if ((f(mid) < 0) == rising) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    class Crossing(val timeMs: Long, val rising: Boolean)

    /** Times in [startMs, endMs] when [value] passes [threshold], found by stepping and then narrowing down. */
    fun crossings(startMs: Long, endMs: Long, threshold: Double, stepMs: Long = 300_000L, value: (Long) -> Double): List<Crossing> {
        val out = ArrayList<Crossing>()
        var a = startMs
        var fa = value(a) - threshold
        while (a < endMs) {
            val b = minOf(a + stepMs, endMs)
            val fb = value(b) - threshold
            if ((fa < 0) != (fb < 0)) {
                val t = bisect(a, b) { value(it) - threshold }
                out += Crossing(t, fb >= 0)
            }
            a = b
            fa = fb
        }
        return out
    }

    class Span(val startMs: Long, val endMs: Long) {
        val minutes: Long get() = (endMs - startMs) / 60_000L
    }

    /** Stretches within [startMs, endMs] where the sky is fully dark (Sun below -18°) and the Moon is below the horizon. */
    fun moonlessDark(startMs: Long, endMs: Long, lat: Double, lon: Double, stepMs: Long = 300_000L): List<Span> {
        val out = ArrayList<Span>()
        var spanStart = -1L
        var t = startMs
        while (t <= endMs) {
            val dark = sunAltitude(t, lat, lon) < ASTRONOMICAL && moonAltitude(t, lat, lon) < moonHorizon(t)
            if (dark && spanStart < 0) spanStart = t
            if (!dark && spanStart >= 0) { out += Span(spanStart, t); spanStart = -1 }
            t += stepMs
        }
        if (spanStart >= 0) out += Span(spanStart, endMs)
        return out.filter { it.minutes >= 10 }
    }

    /** Stretches when the Sun is below -18°, whatever the Moon does. */
    fun astronomicalDark(startMs: Long, endMs: Long, lat: Double, lon: Double, stepMs: Long = 300_000L): List<Span> {
        val out = ArrayList<Span>()
        var spanStart = -1L
        var t = startMs
        while (t <= endMs) {
            val dark = sunAltitude(t, lat, lon) < ASTRONOMICAL
            if (dark && spanStart < 0) spanStart = t
            if (!dark && spanStart >= 0) { out += Span(spanStart, t); spanStart = -1 }
            t += stepMs
        }
        if (spanStart >= 0) out += Span(spanStart, endMs)
        return out.filter { it.minutes >= 10 }
    }

    /** One line advice about the Moon's effect on faint objects. */
    fun moonAdvice(illumination: Double): String = when {
        illumination < 0.1 -> "Almost no moonlight: the best time for faint nebulae and the Milky Way."
        illumination < 0.4 -> "A thin moon: fine for most targets; shoot after it sets for the faintest ones."
        illumination < 0.75 -> "A bright moon washes out faint objects. Wait for moonset or choose bright targets."
        else -> "A very bright moon. Good for the Moon itself and bright clusters; poor for nebulae and the Milky Way."
    }

    fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)
}
