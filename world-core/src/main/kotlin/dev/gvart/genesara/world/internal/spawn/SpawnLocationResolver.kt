package dev.gvart.genesara.world.internal.spawn

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component

/**
 * Resolves a spawn target through the canonical fallback chain so the spawn reducer
 * stays focused on body/position mutation. Order: last-known position (resume) →
 * race-keyed starter node → random spawnable node. Mirrors [SafeNodeResolver][dev.gvart.genesara.world.internal.death.SafeNodeResolver]
 * for the respawn flow.
 */
internal interface SpawnLocationResolver {
    fun resolveFor(agentId: AgentId): NodeId?
}

@Component
internal class SpawnLocationResolverImpl(
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
) : SpawnLocationResolver {

    override fun resolveFor(agentId: AgentId): NodeId? =
        tryResume(agentId)
            ?: tryRaceStarterNode(agentId)
            ?: world.randomSpawnableNode()

    private fun tryResume(agentId: AgentId): NodeId? = world.locationOf(agentId)

    private fun tryRaceStarterNode(agentId: AgentId): NodeId? {
        val agent = agents.find(agentId) ?: return null
        return world.starterNodeFor(agent.race)
    }
}
