package dev.gvart.agenticrpg.world.internal.worldstate

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.internal.body.AgentBody

internal data class WorldState(
    val regions: Map<RegionId, Region>,
    val nodes: Map<NodeId, Node>,
    val positions: Map<AgentId, NodeId>,
    val bodies: Map<AgentId, AgentBody>,
) {

    fun isAdjacent(from: NodeId, to: NodeId): Boolean =
        nodes[from]?.adjacency?.contains(to) == true

    fun moveAgent(agent: AgentId, to: NodeId): WorldState =
        copy(positions = positions + (agent to to))

    fun bodyOf(agent: AgentId): AgentBody? = bodies[agent]

    fun updateBody(agent: AgentId, body: AgentBody): WorldState =
        copy(bodies = bodies + (agent to body))

    companion object {
        val EMPTY = WorldState(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
            bodies = emptyMap(),
        )
    }
}