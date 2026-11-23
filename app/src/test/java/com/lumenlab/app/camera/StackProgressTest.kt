package com.lumenlab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class StackProgressTest {
    @Test
    fun etaUnderNinetySecondsIsInSeconds() {
        assertEquals("8 s", formatEta(8))
        assertEquals("89 s", formatEta(89))
    }

    @Test
    fun etaAtOrOverNinetySecondsRoundsUpToMinutes() {
        assertEquals("2 min", formatEta(90))
        assertEquals("2 min", formatEta(120))
        assertEquals("3 min", formatEta(121))
    }

    @Test
    fun progressTextWithAndWithoutEta() {
        assertEquals("Stacking 12 of 20 · 45%", formatProgress("Stacking 12 of 20", 0.45f, null))
        assertEquals(
            "Stacking · 50% · about 8 s left",
            formatProgress("Stacking", 0.5f, 8),
        )
    }

    @Test
    fun progressFractionIsClamped() {
        assertEquals("X · 100%", formatProgress("X", 1.7f, null))
        assertEquals("X · 0%", formatProgress("X", -1f, null))
    }
}
