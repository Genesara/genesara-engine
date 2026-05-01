package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component

/**
 * Resolves a respawn target through the canonical fallback chain so the respawn reducer
 * stays focused on body/position mutation. Order: explicit checkpoint → race-keyed
 * starter node → random spawnable node. Phase 3 will layer clan-home and city
 * precedence on top of the checkpoint step without touching the reducer.
 */
internal interface SafeNodeResolver {
    fun resolveFor(agentId: AgentId): SafeNodeResolution?
}

internal data class SafeNodeResolution(
    val nodeId: NodeId,
    val fromCheckpoint: Boolean,
)

@Component
internal class SafeNodeResolverImpl(
    private val safeNodes: AgentSafeNodeGateway,
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
) : SafeNodeResolver {

    override fun resolveFor(agentId: AgentId): SafeNodeResolution? =
        tryCheckpoint(agentId)
            ?: tryRaceStarterNode(agentId)
            ?: tryRandomSpawnableNode()

    private fun tryCheckpoint(agentId: AgentId): SafeNodeResolution? =
        safeNodes.find(agentId)?.let { SafeNodeResolution(it, fromCheckpoint = true) }

    private fun tryRaceStarterNode(agentId: AgentId): SafeNodeResolution? {
        val agent = agents.find(agentId) ?: return null
        return world.starterNodeFor(agent.race)?.let { SafeNodeResolution(it, fromCheckpoint = false) }
    }

    private fun tryRandomSpawnableNode(): SafeNodeResolution? =
        world.randomSpawnableNode()?.let { SafeNodeResolution(it, fromCheckpoint = false) }
}
