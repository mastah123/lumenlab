package com.lumenlab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {
    @Test
    fun framesRoundUp() {
        assertEquals(360, SessionPlan.frames(3600, 10.0))
        assertEquals(120, SessionPlan.frames(3600, 30.0))
        assertEquals(124, SessionPlan.frames(3700, 30.0))
        assertEquals(1, SessionPlan.frames(5, 30.0))
        assertEquals(1, SessionPlan.frames(100, 0.0))
    }

    @Test
    fun planAddsOverheadAndStorage() {
        val r = SessionPlan.plan(SessionPlan.Input(1800, 10.0, 4.0, 50_000.0, 100))
        assertEquals(180, r.frames)
        assertEquals(180 * 11.0, r.wallSeconds, 0.01)
        assertEquals(720.0, r.storageMb, 0.01)
        assertEquals(Math.sqrt(180.0), r.noiseGain, 0.001)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun warnsAboutStorageBatteryAndHeat() {
        val r = SessionPlan.plan(SessionPlan.Input(4 * 3600, 15.0, 5.0, 3000.0, 40))
        assertTrue(r.warnings.any { it.contains("free") })
        assertTrue(r.warnings.any { it.contains("battery") })
        assertTrue(r.warnings.any { it.contains("heat") })
    }

    @Test
    fun unknownStorageAndBatteryGiveNoWarnings() {
        val r = SessionPlan.plan(SessionPlan.Input(1200, 20.0, 5.0, null, null))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun textFormats() {
        assertEquals("2 h 5 min", SessionPlan.formatDuration(7500.0))
        assertEquals("3 h", SessionPlan.formatDuration(10800.0))
        assertEquals("45 s", SessionPlan.formatDuration(45.0))
        assertEquals("1.5 GB", SessionPlan.formatMb(1500.0))
        assertEquals("720 MB", SessionPlan.formatMb(720.0))
    }
}
