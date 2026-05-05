package dev.gvart.genesara.world

import java.util.UUID

/**
 * What the death sweep dropped on a given death. Carried inline on the
 * resulting `WorldEvent.AgentDied` so the dying agent learns what they lost
 * without a follow-up read, and on `WorldEvent.ItemDroppedOnGround` so other
 * agents can pick it up via the `pickup` MCP tool.
 *
 * Pickup uses [dropId] as the handle — also the field name in the Redis
 * Hash backing the [GroundItemStore].
 */
sealed interface DroppedItemView {
    val dropId: UUID
    val item: ItemId

    /** A unit dropped from the agent's stackable inventory. */
    data class Stackable(
        override val dropId: UUID,
        override val item: ItemId,
        val quantity: Int,
    ) : DroppedItemView {
        init {
            require(quantity > 0) { "quantity ($quantity) must be positive" }
        }
    }

    /**
     * An equipment instance that was equipped at the moment of death and is
     * now on the ground. The original [instanceId] is preserved so pickup
     * can re-INSERT the row under the new owner without losing the rarity /
     * durability / signature.
     */
    data class Equipment(
        override val dropId: UUID,
        override val item: ItemId,
        val instanceId: UUID,
        val rarity: Rarity,
        val durabilityCurrent: Int,
        val durabilityMax: Int,
        val creatorAgentId: UUID?,
        val createdAtTick: Long,
    ) : DroppedItemView {
        init {
            require(durabilityMax > 0) { "durabilityMax ($durabilityMax) must be positive" }
            require(durabilityCurrent in 0..durabilityMax) {
                "durabilityCurrent ($durabilityCurrent) must be in 0..durabilityMax ($durabilityMax)"
            }
        }
    }
}
