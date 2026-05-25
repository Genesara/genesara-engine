package dev.gvart.genesara.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutlawStateTest {

    @Test
    fun `score below watched threshold is CLEAN`() {
        assertEquals(OutlawState.CLEAN, OutlawState.deriveFrom(score = 0, watchedAt = 25, outlawAt = 100))
        assertEquals(OutlawState.CLEAN, OutlawState.deriveFrom(score = 24, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `score equal to watched threshold is WATCHED — inclusive lower bound`() {
        assertEquals(OutlawState.WATCHED, OutlawState.deriveFrom(score = 25, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `score in middle band is WATCHED`() {
        assertEquals(OutlawState.WATCHED, OutlawState.deriveFrom(score = 50, watchedAt = 25, outlawAt = 100))
        assertEquals(OutlawState.WATCHED, OutlawState.deriveFrom(score = 99, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `score equal to outlaw threshold is OUTLAW — inclusive lower bound`() {
        assertEquals(OutlawState.OUTLAW, OutlawState.deriveFrom(score = 100, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `score above outlaw threshold is OUTLAW`() {
        assertEquals(OutlawState.OUTLAW, OutlawState.deriveFrom(score = 9_999, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `negative score collapses to CLEAN — defensive symmetry`() {
        assertEquals(OutlawState.CLEAN, OutlawState.deriveFrom(score = -5, watchedAt = 25, outlawAt = 100))
    }

    @Test
    fun `inverted thresholds are rejected at the contract boundary`() {
        assertFailsWith<IllegalArgumentException> {
            OutlawState.deriveFrom(score = 50, watchedAt = 100, outlawAt = 25)
        }
    }

    @Test
    fun `equal thresholds collapse to a two-state machine — CLEAN below, OUTLAW at-or-above`() {
        assertEquals(OutlawState.CLEAN, OutlawState.deriveFrom(score = 49, watchedAt = 50, outlawAt = 50))
        assertEquals(OutlawState.OUTLAW, OutlawState.deriveFrom(score = 50, watchedAt = 50, outlawAt = 50))
    }
}
