package com.lumenlab.app.camera

import java.util.Random
import kotlin.math.exp
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakDetectorTest {
    private val w = 400
    private val h = 300

    private class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun sky(seed: Long, starShift: Int, lines: List<Line>, ground: Boolean = true): FloatArray {
        val rnd = Random(seed)
        val a = FloatArray(w * h) { 20f + rnd.nextGaussian().toFloat() * 3f }
        val stars = Random(99)
        repeat(40) {
            val cx = 10 + stars.nextInt(w - 20) + starShift
            val cy = 10 + stars.nextInt(200)
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until w && y in 0 until h) a[y * w + x] += 80f * exp(-(dx * dx + dy * dy) / (2 * 1.4f * 1.4f))
            }
        }
        if (ground) for (y in 250 until h) for (x in 0 until w) a[y * w + x] += 40f
        for (l in lines) {
            val len = hypot(l.x1 - l.x0, l.y1 - l.y0)
            val steps = (len * 2).toInt()
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val cx = l.x0 + (l.x1 - l.x0) * t
                val cy = l.y0 + (l.y1 - l.y0) * t
                for (dy in -1..1) for (dx in -1..1) {
                    val x = (cx + dx).toInt()
                    val y = (cy + dy).toInt()
                    if (x in 0 until w && y in 0 until h) a[y * w + x] = maxOf(a[y * w + x], 90f)
                }
            }
        }
        return a
    }

    private fun prevMask(lines: List<Line> = emptyList()): BooleanArray =
        StreakDetector.dilate(StreakDetector.mask(sky(1, 1, lines), w, h), w, h, 3)

    private val streak = Line(50f, 80f, 200f, 130f)

    @Test
    fun findsALongStreakAndGivesItsDirection() {
        val found = StreakDetector.detect(StreakDetector.mask(sky(2, 0, listOf(streak)), w, h), prevMask(), w, h)
        assertEquals(1, found.size)
        assertEquals(158f, found[0].length, 18f)
        assertEquals(18.4f, found[0].angleDeg, 4f)
        assertEquals(125f, found[0].cx, 8f)
        assertEquals(105f, found[0].cy, 8f)
    }

    @Test
    fun starsAndTheGroundAreNotStreaks() {
        assertTrue(StreakDetector.detect(StreakDetector.mask(sky(3, 0, emptyList()), w, h), prevMask(), w, h).isEmpty())
        // Even with no previous photo to compare with, round stars are not streaks.
        assertTrue(StreakDetector.detect(StreakDetector.mask(sky(3, 0, emptyList(), ground = false), w, h), null, w, h).isEmpty())
    }

    @Test
    fun withNoPreviousPhotoAStreakIsStillFound() {
        val found = StreakDetector.detect(StreakDetector.mask(sky(4, 0, listOf(streak), ground = false), w, h), null, w, h)
        assertEquals(1, found.size)
    }

    @Test
    fun aStreakThatWasAlreadyThereIsStatic() {
        // A bright line in both photos, such as a cable or a lit edge, is not new.
        val prev = prevMask(listOf(streak))
        assertTrue(StreakDetector.detect(StreakDetector.mask(sky(5, 0, listOf(streak)), w, h), prev, w, h).isEmpty())
    }

    @Test
    fun shortStreaksAreIgnoredAndTwoAreCounted() {
        val short = Line(100f, 150f, 118f, 156f)
        assertTrue(StreakDetector.detect(StreakDetector.mask(sky(6, 0, listOf(short)), w, h), prevMask(), w, h).isEmpty())
        val second = Line(260f, 40f, 330f, 190f)
        val two = StreakDetector.detect(StreakDetector.mask(sky(7, 0, listOf(streak, second)), w, h), prevMask(), w, h)
        assertEquals(2, two.size)
    }

    @Test
    fun dilateGrowsByRadius() {
        val m = BooleanArray(w * h)
        m[100 * w + 100] = true
        val d = StreakDetector.dilate(m, w, h, 3)
        assertTrue(d[100 * w + 103] && d[97 * w + 100] && d[103 * w + 103])
        assertTrue(!d[100 * w + 104])
        assertEquals(49, d.count { it })
    }
}
