package dev.gvart.genesara.world

/**
 * A drop currently sitting on the ground at [nodeId], waiting to be picked up.
 * Returned by `WorldQueryGateway.groundItemsAt` and surfaced through the
 * `look_around` MCP tool. The handle agents use to pick it up is `drop.dropId`.
 */
data class GroundItemView(
    val nodeId: NodeId,
    val droppedAtTick: Long,
    val drop: DroppedItemView,
)
