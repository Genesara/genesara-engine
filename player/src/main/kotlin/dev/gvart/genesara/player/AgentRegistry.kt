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

    /**
     * Add [delta] character XP to [agentId] atomically, cascading any number of
     * level-ups in a single round trip. Each level-up bumps `level`, refills
     * `xp_to_next` to `level * XP_PER_LEVEL` (linear), and grants
     * [XP_PER_LEVEL_ATTRIBUTE_POINTS] unspent attribute points.
     *
     * **Level-10 cap.** When an agent without a class reaches level 10, the
     * cascade halts at the level-10 boundary and surplus XP is dropped — they
     * stay at level 10 with `xpCurrent = xpToNext` until they commit a class
     * via `select_class`. Once classed, subsequent grants resume normal
     * leveling. The flag [AddCharacterXpOutcome.cappedAtPendingClassChoice]
     * reports whether this branch fired.
     *
     * Returns one of:
     *  - [AddCharacterXpOutcome.Granted] — success; carries before/after level,
     *    new XP bar state, and the unspent-attribute pool.
     *  - [AddCharacterXpOutcome.NegativeDelta] — [delta] was < 0.
     *  - `null` — the agent row was missing (state corruption); caller logs and
     *    skips, same convention as [applyDeathPenalty].
     *
     * Default implementation throws `NotImplementedError` so test stubs can opt
     * in only when they exercise the XP path; production [JooqAgentRegistry]
     * provides the real implementation.
     */
    fun addCharacterXp(agentId: AgentId, delta: Int): AddCharacterXpOutcome? =
        throw NotImplementedError("addCharacterXp not implemented for this AgentRegistry")

    /**
     * Set the pending class-choice offer for [agentId] to [offer]. Idempotent
     * by design: returns [RecordClassOfferOutcome.AlreadyClassed] if the agent
     * already has a class, [RecordClassOfferOutcome.AlreadyOffered] if a prior
     * offer is still pending. The level-10 emitter calls this exactly once per
     * eligible transition; the result decides whether the
     * [dev.gvart.genesara.player.events.AgentEvent.ClassChoiceOffered] event
     * is published.
     */
    fun recordPendingClassChoice(agentId: AgentId, offer: ClassOffer): RecordClassOfferOutcome =
        throw NotImplementedError("recordPendingClassChoice not implemented for this AgentRegistry")

    /**
     * Commit the agent's class to [classId]. Validates the agent still has no
     * class and that [classId] is one of the two pending offers; clears the
     * pending offer columns on success. The class assignment is forever — no
     * respec — mirroring the perk no-respec rule.
     */
    fun assignClass(agentId: AgentId, classId: AgentClass): AssignClassOutcome =
        throw NotImplementedError("assignClass not implemented for this AgentRegistry")
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

/** Result of [AgentRegistry.addCharacterXp]. */
sealed interface AddCharacterXpOutcome {
    data class Granted(
        val previousLevel: Int,
        val currentLevel: Int,
        val xpCurrent: Int,
        val xpToNext: Int,
        val unspentAttributePoints: Int,
        /**
         * True when the agent reached level 10 with no class assigned and the
         * grant's surplus XP was dropped at the boundary. Caller can ignore;
         * the flag exists for diagnostics and for future "queue actions" UX.
         */
        val cappedAtPendingClassChoice: Boolean,
    ) : AddCharacterXpOutcome

    data object NegativeDelta : AddCharacterXpOutcome
}

/** Result of [AgentRegistry.recordPendingClassChoice]. */
sealed interface RecordClassOfferOutcome {
    data object Recorded : RecordClassOfferOutcome
    data object AlreadyClassed : RecordClassOfferOutcome
    data class AlreadyOffered(val existing: ClassOffer) : RecordClassOfferOutcome
    data object UnknownAgent : RecordClassOfferOutcome
}

/** Result of [AgentRegistry.assignClass]. */
sealed interface AssignClassOutcome {
    data object Assigned : AssignClassOutcome
    data class AlreadyClassed(val existing: AgentClass) : AssignClassOutcome
    data object NoPendingOffer : AssignClassOutcome
    data class NotInOffer(val pending: ClassOffer) : AssignClassOutcome
    data object UnknownAgent : AssignClassOutcome
}
