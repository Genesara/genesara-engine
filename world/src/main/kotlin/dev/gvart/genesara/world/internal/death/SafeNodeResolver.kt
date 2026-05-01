package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component

/**
 * Resolves a respawn target for a dying agent through the canonical fallback
 * chain. Encapsulates "which node should this agent come back at?" so the
 * respawn reducer can stay focused on body / position mutation without
 * juggling four lookup services.
 *
 * Fallback order:
 *   1. The agent's set checkpoint via [AgentSafeNodeGateway.find].
 *   2. The race-keyed starter node via [WorldQueryGateway.starterNodeFor].
 *   3. A random spawnable node via [WorldQueryGateway.randomSpawnableNode].
 *
 * Phase 3 will layer clan home / city precedence on top of (1) without
 * touching the reducer — the resolver becomes the single point of fallback
 * policy.
 */
internal interface SafeNodeResolver {
    /**
     * Returns the resolved respawn target plus whether the agent's explicit
     * checkpoint was honored. Null only when the agent isn't registered or
     * no spawnable node exists at all (a misconfigured world).
     */
    fun resolveFor(agentId: AgentId): SafeNodeResolution?
}

internal data class SafeNodeResolution(
    val nodeId: NodeId,
    /** True if the resolved node came from the agent's set checkpoint. */
    val fromCheckpoint: Boolean,
)

@Component
internal class SafeNodeResolverImpl(
    private val safeNodes: AgentSafeNodeGateway,
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
) : SafeNodeResolver {

    override fun resolveFor(agentId: AgentId): SafeNodeResolution? {
        // 1. Honor the agent's explicit checkpoint when set.
        safeNodes.find(agentId)?.let { return SafeNodeResolution(it, fromCheckpoint = true) }

        // 2. Race-keyed starter. The agent must be registered for race lookup; a
        // missing agent here implies state corruption that the death sweep
        // already caught — but defensively returning null is honest.
        val agent = agents.find(agentId) ?: return null
        world.starterNodeFor(agent.race)?.let { return SafeNodeResolution(it, fromCheckpoint = false) }

        // 3. Last-ditch random spawnable node. Same path the spawn tool falls
        // through to during early dev when starter_nodes hasn't been seeded.
        return world.randomSpawnableNode()?.let { SafeNodeResolution(it, fromCheckpoint = false) }
    }
}
