package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A per-instance carried item. Backed by `world.agent_item_instances`, one row
 * per physical instance. Distinguished from a stackable [agent_inventory] entry
 * by carrying per-instance state (rolled rarity, live durability, creator
 * signature, target building) that prevents merging.
 */
sealed class ItemInstance {

    abstract val instanceId: UUID
    abstract val agentId: AgentId
    abstract val itemId: ItemId
    abstract val createdAtTick: Long

    /**
     * Catalog category for this per-instance item — mirrors the `agent_item_instances.category`
     * discriminator column. Defined on the sealed class so any future subtype (signed crafted
     * items, charged scrolls, named tools) must declare its category at the type level, rather
     * than each projection-site re-deriving it via a string literal.
     */
    abstract val category: ItemCategory

    /**
     * Wearable / wieldable items with rarity, durability, and a creator
     * signature. Lives in the 12-slot equipment grid or in the stash.
     */
    data class Equipment(
        override val instanceId: UUID,
        override val agentId: AgentId,
        override val itemId: ItemId,
        val rarity: Rarity,
        val durabilityCurrent: Int,
        val durabilityMax: Int,
        /** Creator's agent id at craft time. Null for loot drops and pre-genesis items. */
        val creatorAgentId: AgentId?,
        override val createdAtTick: Long,
        /**
         * Slot this instance is currently equipped in, or null if it's sitting in
         * the agent's stash. Two-handed weapons only fill [EquipSlot.MAIN_HAND] —
         * the off-hand "occupation" is enforced by the equip reducer reading the
         * item catalog's `twoHanded` flag, not by a second row.
         */
        val equippedInSlot: EquipSlot? = null,
    ) : ItemInstance() {
        override val category: ItemCategory get() = ItemCategory.EQUIPMENT

        init {
            // Order matters: the more-specific check fires first so a (current=0, max=0)
            // instance gets the clear "max must be positive" message rather than a
            // confusing "0 must be in 0..0" range message.
            require(durabilityMax > 0) { "durabilityMax ($durabilityMax) must be positive" }
            require(durabilityCurrent in 0..durabilityMax) {
                "durabilityCurrent ($durabilityCurrent) must be in 0..durabilityMax ($durabilityMax)"
            }
        }

        /** True when the item has been damaged to zero and should be deleted. */
        val isBroken: Boolean get() = durabilityCurrent == 0
    }

    /**
     * Inventory binding to a specific physical target — today a [GATE]
     * building this key opens. Non-stackable: two keys to two different
     * gates are two separate rows even though they share the catalog
     * [ItemId].
     */
    data class Key(
        override val instanceId: UUID,
        override val agentId: AgentId,
        override val itemId: ItemId,
        val gateInstanceId: UUID,
        override val createdAtTick: Long,
    ) : ItemInstance() {
        override val category: ItemCategory get() = ItemCategory.KEY
    }

    /**
     * Gear equipped onto a mount (saddle, barding, harness). Always owned by
     * an agent — when equipped, [equippedOnMount] + [equippedMountSlot] both
     * point at the mount; when in the agent's stash, both are null. Mount
     * death cascades a delete on rows where [equippedOnMount] = dead mount
     * (gear destroyed with the mount per §16 canon).
     */
    data class MountGear(
        override val instanceId: UUID,
        override val agentId: AgentId,
        override val itemId: ItemId,
        val rarity: Rarity,
        val durabilityCurrent: Int,
        val durabilityMax: Int,
        val creatorAgentId: AgentId?,
        override val createdAtTick: Long,
        val equippedOnMount: UUID? = null,
        val equippedMountSlot: MountSlot? = null,
    ) : ItemInstance() {
        override val category: ItemCategory get() = ItemCategory.MOUNT_GEAR

        init {
            require(durabilityMax > 0) { "durabilityMax ($durabilityMax) must be positive" }
            require(durabilityCurrent in 0..durabilityMax) {
                "durabilityCurrent ($durabilityCurrent) must be in 0..durabilityMax ($durabilityMax)"
            }
            require((equippedOnMount == null) == (equippedMountSlot == null)) {
                "equippedOnMount and equippedMountSlot must both be set or both null"
            }
        }

        val isBroken: Boolean get() = durabilityCurrent == 0
    }
}
