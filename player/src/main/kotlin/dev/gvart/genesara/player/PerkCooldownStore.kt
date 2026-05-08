package dev.gvart.genesara.player

interface PerkCooldownStore {

    fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean

    fun arm(agent: AgentId, perk: PerkId, untilTick: Long)

    /**
     * Tick the cooldown is armed until, or null when no row exists yet (the
     * perk has never been armed for this agent). Distinct from [isReady] so
     * callers that need the wait duration for a rejection message can read it
     * without a follow-up query.
     */
    fun readyAtTick(agent: AgentId, perk: PerkId): Long?
}
