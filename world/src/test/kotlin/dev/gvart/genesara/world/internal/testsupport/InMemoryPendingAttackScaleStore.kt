package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore

/** In-memory test stand-in. Ignores TTL — tests that cover expiry use the Redis impl. */
internal class InMemoryPendingAttackScaleStore : PendingAttackScaleStore {

    val staged = mutableMapOf<AgentId, Int>()

    override fun stage(agent: AgentId, multiplierPct: Int, ttlSeconds: Long) {
        require(ttlSeconds > 0) { "stage() ttlSeconds must be positive (got $ttlSeconds)" }
        staged[agent] = multiplierPct
    }

    override fun consume(agent: AgentId): Int? = staged.remove(agent)

    override fun byAgents(agents: Set<AgentId>): Map<AgentId, Int> =
        agents.mapNotNull { a -> staged[a]?.let { a to it } }.toMap()
}
