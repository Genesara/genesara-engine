package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Per-pair relationship score in `−100..+100`. Read-only abstraction over whatever
 * store backs the relationship ledger. Implementations MUST be symmetric:
 * `scoreBetween(a, b) == scoreBetween(b, a)` for every pair.
 *
 * The real jOOQ-backed implementation lands with the Phase 2 Relationships slice
 * (issue #14). Until then the engine wires a zero-score stub so trade and any
 * other consumers compile and run end-to-end; the trust gate effectively blocks
 * every high-value trade between strangers, which is the safe default.
 */
interface RelationshipLookup {
    fun scoreBetween(a: AgentId, b: AgentId): Int

    companion object {
        /** Stub for tests that don't exercise the trust gate. Returns 0 for every pair. */
        val NoOp: RelationshipLookup = object : RelationshipLookup {
            override fun scoreBetween(a: AgentId, b: AgentId): Int = 0
        }
    }
}
