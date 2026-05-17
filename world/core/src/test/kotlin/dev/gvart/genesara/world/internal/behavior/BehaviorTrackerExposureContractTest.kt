package dev.gvart.genesara.world.internal.behavior

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Phase 4 issue #31 acceptance criterion: the silent counters must never be
 * reachable through any public-facing IO. ADR 0003 (zone split) repurposed the
 * guard from Kotlin `internal` to Modulith package-level boundaries —
 * `BehaviorTracker` + `ActionCategory` live in the `internal/behavior` package
 * which is closed to other Modulith modules. The test below pins the package
 * placement so a refactor that moves either type out of `internal/` has to
 * delete the test deliberately.
 */
class BehaviorTrackerExposureContractTest {

    @Test
    fun `ActionCategory lives in the internal behavior package`() {
        assertTrue(
            ActionCategory::class.qualifiedName!!.startsWith("dev.gvart.genesara.world.internal.behavior."),
        )
    }

    @Test
    fun `BehaviorTracker lives in the internal behavior package`() {
        assertTrue(
            BehaviorTracker::class.qualifiedName!!.startsWith("dev.gvart.genesara.world.internal.behavior."),
        )
    }
}
