package dev.gvart.genesara.player

/**
 * Per-pair signed relationship ledger (mechanics-reference §11 + §19). Score
 * runs [SCORE_MIN]..[SCORE_MAX]; mid-band 0 = neutral. Pairs may be passed in
 * either argument order — the gateway normalizes to the canonical row keyed
 * by `agent_a < agent_b`.
 */
interface RelationshipsGateway {
    /** Atomic clamp-add; saturates at the score bounds, never errors. */
    fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long): RelationshipAdjustmentOutcome

    /**
     * Apply [delta] to every (anchor, other) pair in a single batched upsert.
     * Same clamp + serialization semantics as [adjust]; collapses N witness
     * cascades on a crowded node from N round-trips into one. [others] must
     * not contain [anchor]; duplicates are deduped.
     */
    fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long)

    fun find(a: AgentId, b: AgentId): RelationshipRow?

    /** Every pair [agentId] participates in, keyed by the OTHER agent, ordered by score descending. */
    fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow>

    companion object {
        const val SCORE_MIN = -100
        const val SCORE_MAX = 100

        /** Returns 0 / null for every read; swallows every write. For tests that don't exercise the cascade. */
        val NoOp: RelationshipsGateway = object : RelationshipsGateway {
            override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long) =
                RelationshipAdjustmentOutcome(currentScore = 0)
            override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) = Unit
            override fun find(a: AgentId, b: AgentId): RelationshipRow? = null
            override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = emptyMap()
        }
    }
}

data class RelationshipRow(
    val score: Int,
    val lastChangedAtTick: Long,
)

data class RelationshipAdjustmentOutcome(
    val currentScore: Int,
)
