package com.lumenlab.app.camera

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarAlignTest {
    private val cx = 500f
    private val cy = 375f

    private fun field(seed: Long, n: Int = 55): List<StarAlign.Pt> {
        val rnd = Random(seed)
        return List(n) { StarAlign.Pt(rnd.nextFloat() * 1000f, rnd.nextFloat() * 750f) }
    }

    /** The stars as they would be seen after the sky turned by theta and moved by (tx, ty): the frame is the reference moved backwards. */
    private fun observed(ref: List<StarAlign.Pt>, theta: Float, tx: Float, ty: Float): List<StarAlign.Pt> {
        // ref = R(theta)(cur - c) + c + t  =>  cur = R(-theta)(ref - c - t) + c
        val c = cos(-theta)
        val s = sin(-theta)
        return ref.map { p ->
            val a = p.x - cx - tx
            val b = p.y - cy - ty
            StarAlign.Pt(c * a - s * b + cx, s * a + c * b + cy)
        }
    }

    @Test
    fun shiftOnlyIsStillFound() {
        val ref = field(1)
        val cur = observed(ref, 0f, 12.3f, -7.8f)
        val s = StarAlign.estimate(ref, cur, cx, cy)!!
        assertEquals(0f, s.theta, 0.0008f)
        assertEquals(12.3f, s.tx, 0.3f)
        assertEquals(-7.8f, s.ty, 0.3f)
    }

    @Test
    fun smallTurnIsMeasured() {
        val ref = field(2)
        val theta = 0.004f // about 0.23 degrees, a few minutes of sky
        val cur = observed(ref, theta, 4f, 9f)
        val s = StarAlign.estimate(ref, cur, cx, cy)!!
        assertEquals(theta, s.theta, 0.0003f)
        assertEquals(4f, s.tx, 0.4f)
        assertEquals(9f, s.ty, 0.4f)
    }

    @Test
    fun largeTurnIsFoundBySearching() {
        val ref = field(3)
        val theta = 0.09f // about 5 degrees, around twenty minutes
        val cur = observed(ref, theta, -30f, 22f)
        val s = StarAlign.estimate(ref, cur, cx, cy)!!
        assertEquals(theta, s.theta, 0.002f)
        assertEquals(-30f, s.tx, 1.5f)
        assertEquals(22f, s.ty, 1.5f)
        assertTrue(s.matches >= 40)
    }

    @Test
    fun negativeTurnAndMissingStars() {
        val ref = field(4)
        val cur = observed(ref, -0.05f, 10f, 10f).filterIndexed { i, _ -> i % 4 != 0 } + field(99, 6)
        val s = StarAlign.estimate(ref, cur, cx, cy)!!
        assertEquals(-0.05f, s.theta, 0.002f)
    }

    @Test
    fun unrelatedFieldsDoNotAlign() {
        assertNull(StarAlign.estimate(field(5), field(6), cx, cy))
    }

    @Test
    fun tooFewStarsGiveNothing() {
        assertNull(StarAlign.estimate(field(7, 5), field(7, 5), cx, cy))
    }

    @Test
    fun warpPutsAStarBackWhereItBelongs() {
        val w = 200
        val h = 150
        val ccx = w / 2f
        val ccy = h / 2f
        val theta = 0.05f
        val tx = 6f
        val ty = -4f
        // A bright spot at reference position (150, 60) seen in the frame at the position the transform says.
        val ref = listOf(StarAlign.Pt(150f, 60f))
        // Re-centre the helper's centre for this image size.
        val c = cos(-theta)
        val s = sin(-theta)
        val a = 150f - ccx - tx
        val b = 60f - ccy - ty
        val fx = c * a - s * b + ccx
        val fy = s * a + c * b + ccy
        val src = IntArray(w * h)
        for (dy in -1..1) for (dx in -1..1) src[(fy.toInt() + dy) * w + fx.toInt() + dx] = 0xFFFFFFFF.toInt()
        val dst = IntArray(w * h)
        StarAlign.warp(src, dst, w, h, theta, tx, ty, ccx, ccy)
        var best = 0
        var bx = 0
        var by = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = dst[y * w + x] and 0xFF
            if (v > best) { best = v; bx = x; by = y }
        }
        assertTrue("brightest at $bx,$by", abs(bx - 150) <= 2 && abs(by - 60) <= 2)
    }

    @Test
    fun marginsGrowWithTheTurn() {
        assertTrue(StarAlign.marginX(0.1f, 4000, 3000) > StarAlign.marginX(0.01f, 4000, 3000))
        assertTrue(StarAlign.marginY(0.1f, 4000, 3000) > StarAlign.marginX(0.1f, 4000, 3000))
        assertTrue(StarAlign.negligibleAngle(4000, 3000) < 0.001f)
        assertNotNull(StarAlign.degrees(0.1f))
        assertEquals(-0.2f, StarAlign.widest(0.1f, -0.2f), 1e-6f)
    }
}
