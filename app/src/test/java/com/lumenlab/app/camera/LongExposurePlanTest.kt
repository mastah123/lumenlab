package com.lumenlab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongExposurePlanTest {
    private fun step(ns: Long, honored: Boolean, luma: Double? = null) =
        ExposureStep(requestedNs = ns, appliedNs = if (honored) ns else null, honored = honored, luma = luma)

    private fun result(
        steps: List<ExposureStep>,
        boost: Long? = null,
        error: String? = null,
        darkRoom: Boolean = false,
    ) = ProbeResult("0", steps, 100, 100, boost, error, "", darkRoom)

    @Test
    fun hardwarePathWhenSecondsAreHonored() {
        val v = assess(result(listOf(step(10_000_000L, true), step(1_000_000_000L, true), step(4_000_000_000L, true))))
        assertEquals(ExposurePath.Hardware, v.path)
        assertEquals(4_000_000_000L, v.frameNs)
        assertEquals(listOf(10, 30, 60, 300), v.rows.map { it.targetSeconds })
        assertEquals(listOf(3, 8, 15, 75), v.rows.map { it.frames })
    }

    @Test
    fun stackingPathWhenOnlyShortExposuresAreHonored() {
        val v = assess(result(listOf(step(10_000_000L, true), step(100_000_000L, true), step(1_000_000_000L, false))))
        assertEquals(ExposurePath.Stacking, v.path)
        assertEquals(100_000_000L, v.frameNs)
    }

    @Test
    fun autoBoostPathWhenManualIgnoredButAutoWasLong() {
        val v = assess(result(listOf(step(10_000_000L, false)), boost = 100_000_000L))
        assertEquals(ExposurePath.AutoBoost, v.path)
        assertEquals(100_000_000L, v.frameNs)
    }

    @Test
    fun nonePathHasNoPlanRows() {
        val v = assess(result(listOf(step(10_000_000L, false)), boost = 10_000_000L))
        assertEquals(ExposurePath.None, v.path)
        assertTrue(v.rows.isEmpty())
    }

    @Test
    fun errorGivesNoneWithMessage() {
        val v = assess(result(emptyList(), error = "Camera busy"))
        assertEquals(ExposurePath.None, v.path)
        assertEquals("Camera busy", v.summary)
    }

    @Test
    fun snrGainIsSquareRootOfFrames() {
        val row = assess(result(listOf(step(10_000_000L, true), step(1_000_000_000L, true))))
            .rows.first { it.targetSeconds == 300 }
        assertEquals(300, row.frames)
        assertEquals(Math.sqrt(300.0), row.snrGain, 1e-9)
    }

    @Test
    fun brightnessConfirmedWhenFramesGetBrighter() {
        val r = result(listOf(step(1L, true, 40.0), step(2L, true, 60.0), step(3L, true, 90.0)))
        assertEquals(BrightnessStatus.Confirmed, r.brightnessCheck().status)
    }

    @Test
    fun brightnessInconclusiveWhenClipped() {
        val r = result(listOf(step(1L, true, 235.0), step(2L, true, 240.0)))
        assertEquals(BrightnessStatus.Inconclusive, r.brightnessCheck().status)
    }

    @Test
    fun brightnessInconclusiveWhenItGoesUpAndDown() {
        val r = result(listOf(step(1L, true, 80.0), step(2L, true, 40.0), step(3L, true, 90.0)))
        assertEquals(BrightnessStatus.Inconclusive, r.brightnessCheck().status)
    }

    @Test
    fun brightnessNoDataAndSkipped() {
        assertEquals(BrightnessStatus.NoData, result(listOf(step(1L, true))).brightnessCheck().status)
        assertEquals(BrightnessStatus.Skipped, result(listOf(step(1L, true)), darkRoom = true).brightnessCheck().status)
    }

    @Test
    fun formatExposureShowsSecondsAndFractions() {
        assertEquals("30.0 s", formatExposure(30_000_000_000L))
        assertEquals("1/100 s", formatExposure(10_000_000L))
        assertEquals("0 s", formatExposure(0L))
    }
}
