package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

interface WorldQueryGateway {
    /**
     * Last known node for [agent], regardless of whether they are currently in the world.
     * Used to resume a returning agent at the node where they previously despawned.
     */
    fun locationOf(agent: AgentId): NodeId?

    /**
     * Current node if [agent] is *active* (in the world). `null` if despawned or never spawned.
     */
    fun activePositionOf(agent: AgentId): NodeId?

    fun node(id: NodeId): Node?
    fun region(id: RegionId): Region?

    /**
     * BFS over [Node.adjacency] starting at [origin], returning every node reachable
     * within [radius] hops (inclusive of [origin] itself when radius >= 0).
     */
    fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId>

    /**
     * Returns a random spawnable node id from the world, or `null` if the world has no nodes.
     * Used by the spawn tool to place first-time agents.
     */
    fun randomSpawnableNode(): NodeId?
}
