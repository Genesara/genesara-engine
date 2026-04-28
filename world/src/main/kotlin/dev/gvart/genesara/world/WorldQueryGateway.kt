package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId

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
     * Used by the spawn tool as the final fallback when no race starter node is configured.
     */
    fun randomSpawnableNode(): NodeId?

    /**
     * The designated starter node for [race], or `null` if no starter node has been assigned
     * (table empty during early dev — spawn falls back to [randomSpawnableNode]).
     */
    fun starterNodeFor(race: RaceId): NodeId?

    /**
     * Live body snapshot for [agent] (HP/Stamina/Mana, current and max). `null` if the agent
     * has never been spawned. Read directly from `agent_bodies` so the value reflects the
     * latest committed tick, not the previously-cached in-memory state.
     */
    fun bodyOf(agent: AgentId): BodyView?

    /**
     * Live stackable-inventory snapshot for [agent]. Always returns a non-null view —
     * an empty list when the agent has no stacks. Read directly from `agent_inventory`.
     */
    fun inventoryOf(agent: AgentId): InventoryView
}
