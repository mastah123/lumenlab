package com.lumenlab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarExposureTest {
    private fun camera(
        focal: List<Float> = listOf(5.4f),
        aperture: List<Float> = listOf(1.8f),
        widthMm: Float? = 8.0f,
        widthPx: Int = 4000,
    ) = CameraInfo(
        id = "0", facing = "Back", hardwareLevel = "FULL", sensorWidth = widthPx, sensorHeight = 3000,
        focalLengthsMm = focal, apertures = aperture, exposureRangeNs = 1_000_000L..10_000_000_000L,
        isoRange = 100..3200, minFocusDiopters = 10f, manualSensor = true, manualFocus = true,
        manualPostProcessing = true, raw = false, aeCompRangeEv = null, awbModeCount = 1,
        formats = emptyList(), physicalIds = emptyList(), physicalWidthMm = widthMm,
    )

    private val limits = ControlLimits(
        camera = camera(), minExposureNs = 1_000_000L, maxExposureNs = 1_000_000_000L,
        exposureVerified = false, isoMin = 100, isoMax = 3200, manualAvailable = true,
        manualFocus = true, minFocusDiopters = 10f,
    )

    @Test
    fun pixelPitch() {
        assertEquals(2.0, StarExposure.pixelPitchUm(8.0f, 4000)!!, 1e-6)
        assertNull(StarExposure.pixelPitchUm(null, 4000))
        assertNull(StarExposure.pixelPitchUm(8.0f, 0))
    }

    @Test
    fun npfMatchesTheFormula() {
        // (35 × 1.8 + 30 × 2) ÷ 5.4 = 22.78 s
        assertEquals(22.777, StarExposure.npfSeconds(5.4, 1.8, 2.0), 0.01)
    }

    @Test
    fun fiveHundredRuleUsesEquivalentFocalLength() {
        // 5.4 mm on an 8 mm wide sensor is 24.3 mm equivalent, so 500 ÷ 24.3 = 20.6 s
        assertEquals(20.57, StarExposure.fiveHundredSeconds(5.4, 8.0f), 0.05)
    }

    @Test
    fun suggestPrefersNpfAndFallsBack() {
        assertEquals("NPF rule", StarExposure.suggest(camera())!!.method)
        assertEquals("500 rule", StarExposure.suggest(camera(aperture = emptyList()))!!.method)
        assertEquals("500 rule", StarExposure.suggest(camera(widthPx = 0))!!.method)
        assertNull(StarExposure.suggest(camera(widthMm = null)))
        assertNull(StarExposure.suggest(camera(focal = emptyList())))
    }

    @Test
    fun suggestionIsKeptInsideTheLimits() {
        assertEquals(1_000_000_000L, StarExposure.clampNs(22.7, limits))
        assertEquals(1_000_000L, StarExposure.clampNs(0.0, limits))
        assertEquals(2_000_000_000L, StarExposure.clampNs(2.0, limits.copy(maxExposureNs = 5_000_000_000L)))
    }

    @Test
    fun exposureFractionReversesExposureAt() {
        assertEquals(0f, limits.exposureFraction(1_000_000L), 1e-6f)
        assertEquals(1f, limits.exposureFraction(1_000_000_000L), 1e-6f)
        assertEquals(1f, limits.exposureFraction(9_000_000_000L), 1e-6f)
        val ns = 50_000_000L
        assertEquals(ns.toDouble(), limits.exposureAt(limits.exposureFraction(ns)).toDouble(), ns * 0.001)
    }

    private fun picture(sigma: Double?): IntArray {
        val w = 40
        val h = 30
        return IntArray(w * h) { i ->
            val x = i % w - 20
            val y = i / w - 15
            if (sigma == null) 10 else (10 + 240 * Math.exp(-(x * x + y * y) / (2 * sigma * sigma))).toInt()
        }
    }

    @Test
    fun sharperStarScoresHigher() {
        val sharp = focusSharpness(picture(0.8), 40, 30)
        val soft = focusSharpness(picture(3.0), 40, 30)
        assertTrue("sharp=$sharp soft=$soft", sharp > soft)
        assertEquals(0.0, focusSharpness(picture(null), 40, 30), 1e-9)
    }

    @Test
    fun sharpnessIgnoresBadInput() {
        assertEquals(0.0, focusSharpness(IntArray(4), 2, 2), 1e-9)
        assertNotNull(focusSharpness(picture(1.0), 40, 30))
    }
}
