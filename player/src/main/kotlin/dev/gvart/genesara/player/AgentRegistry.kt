package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

interface AgentRegistry {
    fun find(id: AgentId): Agent?
    fun listForOwner(owner: PlayerId): List<Agent>

    /** Hard-deletes the agent row. CASCADE clears player-side rows (profile, skills). Returns true if a row was removed. */
    fun delete(agentId: AgentId): Boolean = throw NotImplementedError("delete not implemented for this AgentRegistry")

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

    /**
     * Spend unspent attribute points by adding non-negative [deltas] to the matching
     * attributes atomically. Validates `unspentAttributePoints >= sum(deltas.values)`,
     * decrements the pool by the sum, bumps each attribute, and recomputes the
     * derived `agent_profiles.{maxHp,maxStamina,maxMana}` via [AttributeDerivation].
     * Current pool values (HP / Stamina / Mana) are NOT auto-restored — leveling
     * Constitution doesn't heal the agent.
     *
     * Returns one of:
     *  - [AllocateAttributesOutcome.Allocated] — success; carries the post-allocation
     *    [AgentAttributes], remaining unspent points, and the milestone thresholds
     *    (50 / 100 / 200) crossed by this allocation.
     *  - [AllocateAttributesOutcome.NegativeDelta] — at least one delta was < 0.
     *    No respec / unallocate in v1.
     *  - [AllocateAttributesOutcome.InsufficientPoints] — sum of deltas exceeds the
     *    agent's unspent pool.
     *  - `null` — the agent row was missing (state corruption); caller logs and skips,
     *    same convention as [applyDeathPenalty].
     *
     * Default implementation throws `NotImplementedError` so test stubs can opt in
     * only when they exercise the allocation path; production [JooqAgentRegistry]
     * provides the real implementation.
     */
    fun allocateAttributes(
        agentId: AgentId,
        deltas: Map<Attribute, Int>,
    ): AllocateAttributesOutcome? =
        throw NotImplementedError("allocateAttributes not implemented for this AgentRegistry")
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

/** Result of [AgentRegistry.allocateAttributes]. */
sealed interface AllocateAttributesOutcome {
    /** Successful allocation. Carries the post-allocation snapshot for the caller to surface. */
    data class Allocated(
        val attributes: AgentAttributes,
        val remainingUnspent: Int,
        val crossedMilestones: List<AttributeMilestoneCrossing>,
    ) : AllocateAttributesOutcome

    /** At least one delta was negative — rejected up-front before the DB round trip. */
    data object NegativeDelta : AllocateAttributesOutcome

    /**
     * Sum of deltas exceeded the agent's unspent pool. `requested` is `Long` so the
     * rejection can faithfully report values that overflow `Int` (e.g. an attacker
     * sending two near-`Int.MAX_VALUE` deltas).
     */
    data class InsufficientPoints(val unspent: Int, val requested: Long) : AllocateAttributesOutcome
}

/** A single (attribute, milestone) pair crossed by an allocation. */
data class AttributeMilestoneCrossing(val attribute: Attribute, val milestone: Int)
