package com.lumenlab.app.camera

import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrFusionTest {
    private val w = 128
    private val h = 96

    /** Light of the scene: a dark left half and a bright right half, each with fine texture. */
    private fun radiance(): FloatArray {
        val rnd = Random(11)
        return FloatArray(w * h) { i ->
            val base = if (i % w < w / 2) 6f else 320f
            base * (0.7f + 0.6f * rnd.nextFloat())
        }
    }

    /** A photo with the camera's brightness [gain], as a gamma curve that clips at white. */
    private fun photo(light: FloatArray, gain: Float): IntArray = IntArray(w * h) { i ->
        val v = (255.0 * (light[i] * gain / 255.0).coerceAtMost(1.0).pow(1 / 2.2)).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun spread(img: IntArray, xFrom: Int, xTo: Int): Double {
        val v = ArrayList<Double>()
        for (y in 4 until h - 4) for (x in xFrom until xTo) v += (img[y * w + x] and 0xFF).toDouble()
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    }

    private fun fuse(frames: List<IntArray>): IntArray {
        val f = HdrFuser(w, h)
        frames.forEach { f.addWeights(it) }
        frames.forEachIndexed { i, img -> f.accumulate(i, img) }
        return f.result()
    }

    @Test
    fun fusedPictureKeepsDetailInDarkAndBrightParts() {
        val light = radiance()
        val under = photo(light, 0.2f)
        val normal = photo(light, 1f)
        val over = photo(light, 5f)
        val fused = fuse(listOf(under, normal, over))
        // Dark part: the bright photo shows the texture best. Bright part: the dark photo does.
        val darkBest = maxOf(spread(under, 8, 56), spread(normal, 8, 56), spread(over, 8, 56))
        val brightBest = maxOf(spread(under, 72, 120), spread(normal, 72, 120), spread(over, 72, 120))
        assertTrue("dark ${spread(fused, 8, 56)} vs $darkBest", spread(fused, 8, 56) > darkBest * 0.6)
        assertTrue("bright ${spread(fused, 72, 120)} vs $brightBest", spread(fused, 72, 120) > brightBest * 0.6)
        // Nothing is left black or clipped on average.
        val darkMean = (8 until 56).sumOf { x -> (10 until 80).sumOf { y -> (fused[y * w + x] and 0xFF) } } / (48.0 * 70)
        val brightMean = (72 until 120).sumOf { x -> (10 until 80).sumOf { y -> (fused[y * w + x] and 0xFF) } } / (48.0 * 70)
        assertTrue("darkMean $darkMean", darkMean > 30)
        assertTrue("brightMean $brightMean", brightMean < 235)
    }

    @Test
    fun onePhotoComesBackUnchanged() {
        val img = photo(radiance(), 1f)
        val out = fuse(listOf(img))
        var err = 0.0
        for (i in img.indices) err += kotlin.math.abs((img[i] and 0xFF) - (out[i] and 0xFF))
        assertTrue("mean error ${err / img.size}", err / img.size < 1.5)
    }

    @Test
    fun weightsFavourWellExposedPixels() {
        val flatDark = IntArray(w * h) { 0xFF050505.toInt() }
        val flatMid = IntArray(w * h) { 0xFF808080.toInt() }
        val a = HdrFuser.weightMap(flatDark, w, h)[50 * w + 50]
        val b = HdrFuser.weightMap(flatMid, w, h)[50 * w + 50]
        assertTrue(b > a)
        assertTrue(a > 0f)
    }

    @Test
    fun reduceAndExpandKeepSizesAndFlatValues() {
        val flat = FloatArray(33 * 21) { 0.4f }
        val small = HdrFuser.reduce(flat, 33, 21)
        assertEquals(17 * 11, small.size)
        assertTrue(small.all { kotlin.math.abs(it - 0.4f) < 1e-5f })
        val big = HdrFuser.expand(small, 17, 11, 33, 21)
        assertEquals(33 * 21, big.size)
        assertTrue(big.all { kotlin.math.abs(it - 0.4f) < 1e-5f })
    }

    @Test
    fun bracketSteps() {
        assertEquals(listOf(-2f, 0f, 2f), Bracket.evSteps(3, 2f))
        assertEquals(listOf(-3f, -1.5f, 0f, 1.5f, 3f), Bracket.evSteps(5, 1.5f))
    }

    @Test
    fun bracketPlanChangesTimeThenIso() {
        val plan = Bracket.plan(10_000_000L, 200, listOf(-2f, 0f, 2f), 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(2_500_000L, plan[0].exposureNs)
        assertEquals(200, plan[0].iso)
        assertEquals(10_000_000L, plan[1].exposureNs)
        assertEquals(40_000_000L, plan[2].exposureNs)
        // When the time cannot go lower, ISO takes the rest.
        val limited = Bracket.plan(2_000_000L, 400, listOf(-2f), 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(1_000_000L, limited[0].exposureNs)
        assertEquals(200, limited[0].iso)
        // When the time cannot go higher, ISO takes the rest too.
        val capped = Bracket.plan(500_000_000L, 100, listOf(2f), 1_000_000L, 1_000_000_000L, 100, 3200)
        assertEquals(1_000_000_000L, capped[0].exposureNs)
        assertEquals(200, capped[0].iso)
    }

    @Test
    fun normalisedBrightnessIsComparable() {
        val a = FloatArray(1000) { it % 50 + 10f }
        val b = FloatArray(1000) { (it % 50 + 10f) * 3f + 40f }
        val na = HdrFuser.normalise(a)
        val nb = HdrFuser.normalise(b)
        assertEquals(0.0, na.average(), 1e-3)
        for (i in na.indices step 97) assertEquals(na[i], nb[i], 1e-3f)
        assertEquals(1000, HdrFuser.normalise(FloatArray(1000) { 5f }).size)
    }
}
