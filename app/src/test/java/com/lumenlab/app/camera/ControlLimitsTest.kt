package com.lumenlab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlLimitsTest {
    private val camera = CameraInfo(
        id = "0", facing = "Back", hardwareLevel = "FULL", sensorWidth = 4000, sensorHeight = 3000,
        focalLengthsMm = listOf(5f), apertures = listOf(1.8f), exposureRangeNs = 1_000_000L..10_000_000_000L,
        isoRange = 100..3200, minFocusDiopters = 10f, manualSensor = true, manualFocus = true,
        manualPostProcessing = true, raw = false, aeCompRangeEv = null, awbModeCount = 1,
        formats = emptyList(), physicalIds = emptyList(),
    )

    private val limits = ControlLimits(
        camera = camera, minExposureNs = 1_000_000L, maxExposureNs = 1_000_000_000L,
        exposureVerified = true, isoMin = 100, isoMax = 3200, manualAvailable = true,
        manualFocus = true, minFocusDiopters = 10f,
    )

    @Test
    fun exposureEndsMatchLimits() {
        assertEquals(1_000_000L, limits.exposureAt(0f))
        assertEquals(1_000_000_000L, limits.exposureAt(1f))
    }

    @Test
    fun exposureMiddleIsLogarithmic() {
        // geometric mean of 1 ms and 1 s is about 31.6 ms
        val mid = limits.exposureAt(0.5f)
        assertEquals(31_622_776L.toDouble(), mid.toDouble(), 1000.0)
    }

    @Test
    fun exposureFractionIsClamped() {
        assertEquals(1_000_000L, limits.exposureAt(-3f))
        assertEquals(1_000_000_000L, limits.exposureAt(9f))
    }

    @Test
    fun isoEndsAndRoundTrip() {
        assertEquals(100, limits.isoAt(0f))
        assertEquals(3200, limits.isoAt(1f))
        assertEquals(0.5f, limits.isoFraction(limits.isoAt(0.5f)), 0.02f)
    }

    @Test
    fun isoFractionClampsAndHandlesFlatRange() {
        assertEquals(0f, limits.isoFraction(50), 1e-6f)
        assertEquals(1f, limits.isoFraction(99999), 1e-6f)
        val flat = limits.copy(isoMin = 100, isoMax = 100)
        assertEquals(0f, flat.isoFraction(100), 1e-6f)
        assertEquals(100, flat.isoAt(0.7f))
    }
}
