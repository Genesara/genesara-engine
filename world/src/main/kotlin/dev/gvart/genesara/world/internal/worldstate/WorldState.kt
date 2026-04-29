package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory

internal data class WorldState(
    val regions: Map<RegionId, Region>,
    val nodes: Map<NodeId, Node>,
    val positions: Map<AgentId, NodeId>,
    val bodies: Map<AgentId, AgentBody>,
    val inventories: Map<AgentId, AgentInventory>,
) {

    fun isAdjacent(from: NodeId, to: NodeId): Boolean =
        nodes[from]?.adjacency?.contains(to) == true

    fun moveAgent(agent: AgentId, to: NodeId): WorldState =
        copy(positions = positions + (agent to to))

    fun bodyOf(agent: AgentId): AgentBody? = bodies[agent]

    fun isOnline(agent: AgentId): Boolean = agent in positions

    fun updateBody(agent: AgentId, body: AgentBody): WorldState =
        copy(bodies = bodies + (agent to body))

    fun inventoryOf(agent: AgentId): AgentInventory =
        inventories[agent] ?: AgentInventory.EMPTY

    fun updateInventory(agent: AgentId, inventory: AgentInventory): WorldState =
        copy(inventories = inventories + (agent to inventory))

    companion object {
        val EMPTY = WorldState(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
            bodies = emptyMap(),
            inventories = emptyMap(),
        )
    }
}
