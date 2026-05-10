package dev.gvart.genesara.world.internal.behavior

import org.junit.jupiter.api.Test
import kotlin.reflect.KVisibility
import kotlin.test.assertEquals

/**
 * Phase 4 issue #31 acceptance criterion: the silent counters must never be
 * reachable through any public-facing IO. The strongest guard is Kotlin's
 * `internal` modifier — a dependent module that imports either type fails to
 * compile because the symbol is invisible across module boundaries. This
 * fixture pins that contract so a future "make it public for X" change has to
 * delete the test deliberately, not silently flip the visibility.
 */
class BehaviorTrackerExposureContractTest {

    @Test
    fun `ActionCategory stays internal to the world module`() {
        assertEquals(KVisibility.INTERNAL, ActionCategory::class.visibility)
    }

    @Test
    fun `BehaviorTracker stays internal to the world module`() {
        assertEquals(KVisibility.INTERNAL, BehaviorTracker::class.visibility)
    }
}
