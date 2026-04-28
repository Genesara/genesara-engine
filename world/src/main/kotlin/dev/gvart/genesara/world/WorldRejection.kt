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
    /** The agent's current node terrain doesn't list [item] among its gatherables. */
    data class ResourceNotAvailableHere(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection
    /** Agent tried to consume / use an item they don't own. */
    data class ItemNotInInventory(val agent: AgentId, val item: ItemId) : WorldRejection
    /** Agent tried to consume an item that has no consumable effect (e.g. WOOD). */
    data class ItemNotConsumable(val item: ItemId) : WorldRejection
}
