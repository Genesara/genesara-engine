package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

sealed interface WorldRejection {
    data class UnknownAgent(val agent: AgentId) : WorldRejection
    data class UnknownRegion(val region: RegionId) : WorldRejection
    data class UnknownNode(val node: NodeId) : WorldRejection
    data class UnknownProfile(val agent: AgentId) : WorldRejection
    data class UnknownItem(val item: ItemId) : WorldRejection
    data class AlreadySpawned(val agent: AgentId) : WorldRejection
    data class NotAdjacent(val from: NodeId, val to: NodeId) : WorldRejection
    data class NotEnoughStamina(
        val agent: AgentId,
        val required: Int,
        val available: Int,
    ) : WorldRejection
    /** The destination region has no biome or climate set yet (admin hasn't painted it). */
    data class UnpaintedRegion(val region: RegionId) : WorldRejection
    /** Agent attempted a presence-bound action while not in the world. */
    data class NotInWorld(val agent: AgentId) : WorldRejection
    /**
     * The agent's current node has no live deposit for [item]. Two underlying causes
     * collapse to this single rejection:
     *
     *  - The terrain has no spawn rule for the item (wrong biome / wrong terrain
     *    altogether).
     *  - The terrain has a rule with `spawn-chance < 1.0` and this specific node lost
     *    the roll at world-paint time.
     *
     * Either way the agent's response is the same: try a different node (possibly a
     * different terrain). [NodeResourceDepleted] is reserved for "this node had it,
     * mined out" — a different signal an agent can react to (wait for regen / move on).
     */
    data class ResourceNotAvailableHere(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection
    /**
     * The node had a deposit of [item] but its live quantity is zero. Different from
     * [ResourceNotAvailableHere] because the deposit may regenerate (organic items)
     * or stay gone forever (ore / stone / coal / gem / salt) — strategic responses
     * differ accordingly.
     */
    data class NodeResourceDepleted(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection
    /** Agent tried to consume / use an item they don't own. */
    data class ItemNotInInventory(val agent: AgentId, val item: ItemId) : WorldRejection
    /** Agent tried to consume an item that has no consumable effect (e.g. WOOD). */
    data class ItemNotConsumable(val item: ItemId) : WorldRejection
    /**
     * Agent invoked the wrong verb for [item]. Items declare a `gathering-skill`; the
     * verb that trains that skill is the only valid one for the item:
     *
     *  - `gather` accepts FORAGING / LUMBERJACKING / FISHING items.
     *  - `mine` accepts MINING items.
     *
     * [expectedVerb] is the verb the agent should use instead. Surfaced so the agent
     * can correct without round-tripping through the catalog.
     */
    data class WrongVerbForItem(
        val agent: AgentId,
        val item: ItemId,
        val expectedVerb: String,
    ) : WorldRejection
    /** Agent tried to `drink` on a terrain not tagged as a water source (e.g. FOREST). */
    data class NotAWaterSource(val agent: AgentId, val node: NodeId) : WorldRejection
    /**
     * Agent tried to `move` onto a tile whose terrain is non-traversable (e.g. OCEAN
     * before boats unlock in Phase 3, or CLIFFSIDE). Different from [NotAdjacent]
     * because the tile is reachable on the graph, just not enterable on foot.
     */
    data class TerrainNotTraversable(
        val agent: AgentId,
        val node: NodeId,
        val terrain: Terrain,
    ) : WorldRejection

    /**
     * Agent called the `respawn` MCP tool while not actually dead — either their
     * body is above 0 HP, or they're still positioned in the world. Distinct
     * from [NotInWorld] (which is "not spawned at all") because a dead agent IS
     * unpositioned, but for a different reason — surfacing both lets the agent
     * branch correctly.
     */
    data class NotDead(val agent: AgentId) : WorldRejection

    /**
     * Respawn resolved to no spawnable site at all — the agent has no
     * checkpoint, the race has no starter node, and `randomSpawnableNode()`
     * returned null (a misconfigured world with zero traversable nodes).
     * Distinct from [UnknownNode] which means a specific node id is gone.
     */
    data class NoSpawnableNode(val agent: AgentId) : WorldRejection

    /**
     * Adding the requested items would push the agent's total carried weight
     * past the Strength-driven cap. [requested] is the would-be total grams
     * (existing stowed + equipped + the new add); [capacity] is
     * `Strength × carryGramsPerStrengthPoint`. Both surfaced so an agent can
     * see the gap without round-tripping through inventory + catalog.
     */
    data class OverEncumbered(
        val agent: AgentId,
        val requested: Int,
        val capacity: Int,
    ) : WorldRejection
}
