package dev.gvart.genesara.player

/**
 * Mechanics-reference §11 outlaw state machine: a 3-bucket projection of the
 * agent's `outlaw_misconduct_score`. Score is the source of truth (decayed
 * by the world-tick sweep); state is the cheap-to-read cache the registry
 * keeps in sync on every misconduct write or decay pass.
 *
 *  - [CLEAN]   — score below the watched threshold; no PvP-misconduct flag.
 *  - [WATCHED] — score in `[watchedAt, outlawAt)`; a step short of full outlaw.
 *  - [OUTLAW]  — score `>= outlawAt`; downstream NPC effects (Phase 3 #26)
 *                read this to refuse trades / flag KOS.
 */
enum class OutlawState {
    CLEAN,
    WATCHED,
    OUTLAW;

    companion object {
        /**
         * Pure derivation. `outlawAt` MUST be `>= watchedAt`; the registry
         * validates this at the call site so a caller-supplied inversion is
         * caught with a clear contract violation rather than producing a
         * silently-wrong state. Score is clamped at zero by the schema; a
         * negative score still maps to [CLEAN] for defensive symmetry.
         */
        fun deriveFrom(score: Int, watchedAt: Int, outlawAt: Int): OutlawState {
            require(outlawAt >= watchedAt) {
                "outlawAt ($outlawAt) must be >= watchedAt ($watchedAt)"
            }
            return when {
                score >= outlawAt -> OUTLAW
                score >= watchedAt -> WATCHED
                else -> CLEAN
            }
        }
    }
}

/**
 * Result of [AgentRegistry.adjustMisconduct] (single-row) and
 * [AgentRegistry.decayMisconductScores] (batched). Carries the pre/post
 * score+state so the world-side dispatcher emits `OutlawStateChanged` only
 * when [didTransition] flips. A no-op write (delta = 0, same bucket) still
 * returns a valid outcome with `oldState == newState`.
 */
data class MisconductOutcome(
    val agentId: AgentId,
    val oldScore: Int,
    val newScore: Int,
    val oldState: OutlawState,
    val newState: OutlawState,
) {
    val didTransition: Boolean get() = oldState != newState
}
