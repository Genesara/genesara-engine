package dev.gvart.genesara.world.internal.abilities

import dev.gvart.genesara.player.AgentId

/**
 * Authoritative store for the one-shot damage multiplier (whole percent) staged
 * by an `ActiveAbility` with `effectKind = SCALE_NEXT_ATTACK`. Per-call TTL
 * closes the active-ability-buff-expiry hole the in-memory `WorldState` field
 * could never bound — see Q10 in `docs/shard-readiness-sequence.md`.
 */
internal interface PendingAttackScaleStore {

    fun stage(agent: AgentId, multiplierPct: Int, ttlSeconds: Long)

    fun consume(agent: AgentId): Int?

    fun byAgents(agents: Set<AgentId>): Map<AgentId, Int>
}
