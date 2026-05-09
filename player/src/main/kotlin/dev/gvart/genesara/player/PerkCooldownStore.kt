package dev.gvart.genesara.player

interface PerkCooldownStore {

    fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean

    /**
     * Arms the (agent, perk) cooldown until [untilTick]. [currentTick] is taken
     * so the Redis-backed implementation can guard against cooldown durations
     * exceeding `T_persist` — anything that long must write-through to the DB
     * to survive a Redis flush, and is intentionally rejected here as a tripwire
     * against silently arming a perk Redis cannot durably hold.
     */
    fun arm(agent: AgentId, perk: PerkId, untilTick: Long, currentTick: Long)

    /**
     * Tick the cooldown is armed until, or null when no row exists yet (the
     * perk has never been armed for this agent). Distinct from [isReady] so
     * callers that need the wait duration for a rejection message can read it
     * without a follow-up query.
     */
    fun readyAtTick(agent: AgentId, perk: PerkId): Long?

    /**
     * Batched read for the per-tick `WorldStateRepository` slice. Returns one
     * inner map per agent that has at least one armed cooldown; agents with no
     * armed cooldowns are absent from the outer map.
     */
    fun byAgents(agents: Set<AgentId>): Map<AgentId, Map<PerkId, Long>>
}
