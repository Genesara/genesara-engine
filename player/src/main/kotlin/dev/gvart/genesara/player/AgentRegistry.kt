package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

interface AgentRegistry {
    fun find(id: AgentId): Agent?
    fun findByToken(token: String): Agent?
    fun listForOwner(owner: PlayerId): List<Agent>

    /**
     * Apply the canonical death penalty atomically.
     *
     *  - **Partial XP-bar** (`xpCurrent > 0`): subtract `min(xpCurrent, xpLossOnDeath)`
     *    from the agent's character XP. No de-level; the level / unspent points stay.
     *  - **Empty XP-bar** (`xpCurrent == 0`): de-level (`level = max(1, level - 1)`)
     *    and consume one penalty point — preferring the agent's `unspentAttributePoints`
     *    pool, and falling back to decrementing the highest allocated attribute by 1
     *    (ties broken by [Attribute.ordinal]) when the pool is empty.
     *
     * Returns a [DeathPenaltyOutcome] reporting what was actually consumed so the
     * death sweep can stuff those values into the emitted [WorldEvent.AgentDied]. A
     * null return means the agent was missing from the registry (state corruption);
     * caller logs and skips.
     *
     * Default implementation throws `NotImplementedError` so test stubs can opt in
     * only when they exercise the death path; production [JooqAgentRegistry]
     * provides the real implementation.
     */
    fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
        throw NotImplementedError("applyDeathPenalty not implemented for this AgentRegistry")
}

/**
 * Result of [AgentRegistry.applyDeathPenalty]. Either the partial-bar branch fired
 * (XP-only loss) or the empty-bar branch (de-level + one penalty point). The
 * fields tell the caller which.
 */
data class DeathPenaltyOutcome(
    /** XP subtracted from `xpCurrent`. Always >= 0. */
    val xpLost: Int,
    /** True when the empty-bar branch fired and the agent lost a level. */
    val deleveled: Boolean,
    /**
     * Penalty point consumed on de-level. `UNSPENT` when an unspent pool point
     * was consumed; the named attribute when the pool was empty and the
     * highest stat was docked; null when no de-level fired (partial-bar branch).
     */
    val attributePointLost: AttributePointLoss?,
)

/** Where the de-level penalty point was taken from. */
sealed interface AttributePointLoss {
    /** Consumed an unspent attribute point. */
    data object Unspent : AttributePointLoss
    /** Decremented an allocated attribute by 1 because the unspent pool was empty. */
    data class Allocated(val attribute: Attribute) : AttributePointLoss
}
