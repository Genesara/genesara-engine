package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Equip / unequip operations on a mount's gear slots (SADDLE, BARDING, HARNESS).
 *
 * Sync, mirroring [EquipmentService]: per-mount-slot mutations don't race with
 * tick reducers in any meaningful way — the SADDLE / BARDING / HARNESS effect
 * reads happen at movement / attack / cargo time, and the partial unique index
 * on `(equipped_on_mount_id, equipped_mount_slot)` is the authoritative fence.
 *
 * Same-node + owner-gated: an agent must own both the [ItemInstance.MountGear]
 * instance and the target mount, and be standing at the mount's node, before
 * the gear moves onto the mount. Mount rustling (open riding) is intentionally
 * NOT extended to gear — saddle theft would be too easy and the design pin
 * says owner-only.
 */
interface EquipMountGearService {

    /**
     * Move [instanceId] (a [ItemInstance.MountGear] owned by [agentId]) onto
     * [mountId]'s [slot]. Validation order — earlier checks surface clearer
     * signals to the agent:
     *
     *  1. instance exists                              → [EquipMountGearRejection.INSTANCE_NOT_FOUND]
     *  2. instance is MOUNT_GEAR                       → [EquipMountGearRejection.NOT_MOUNT_GEAR]
     *  3. instance belongs to caller                   → [EquipMountGearRejection.NOT_YOUR_INSTANCE]
     *  4. mount exists                                 → [EquipMountGearRejection.UNKNOWN_MOUNT]
     *  5. caller is at the mount's node                → [EquipMountGearRejection.NOT_SAME_NODE]
     *  6. catalog item exists for the instance         → [EquipMountGearRejection.UNKNOWN_ITEM]
     *  7. slot is in the item's [Item.mountSlots]      → [EquipMountGearRejection.INVALID_SLOT_FOR_ITEM]
     *  8. slot is in the mount's [MountDef.gearSlots]  → [EquipMountGearRejection.SLOT_NOT_ON_MOUNT]
     *  9. instance not already equipped anywhere       → [EquipMountGearRejection.ALREADY_EQUIPPED]
     * 10. target (mount, slot) is empty                → [EquipMountGearRejection.SLOT_OCCUPIED]
     */
    fun equipMountGear(
        agentId: AgentId,
        instanceId: UUID,
        mountId: MountId,
        slot: MountSlot,
    ): EquipMountGearResult

    /**
     * Clear [slot] on [mountId]. Open to any agent same-node with the mount;
     * returns the cleared instance back to its owning agent's stash. Returns
     * [UnequipMountGearResult.SlotEmpty] when no gear sits in the slot or the
     * mount doesn't exist.
     */
    fun unequipMountGear(
        agentId: AgentId,
        mountId: MountId,
        slot: MountSlot,
    ): UnequipMountGearResult

    /**
     * Mount gear currently occupying each slot on [mountId], keyed by slot.
     * Slots with no equipped instance are absent from the map.
     */
    fun equippedOnMount(mountId: MountId): Map<MountSlot, ItemInstance.MountGear>
}

/** Outcome of an [EquipMountGearService.equipMountGear] call. */
sealed interface EquipMountGearResult {
    data class Equipped(val instance: ItemInstance.MountGear) : EquipMountGearResult
    data class Rejected(val reason: EquipMountGearRejection, val detail: String? = null) : EquipMountGearResult
}

/** Outcome of an [EquipMountGearService.unequipMountGear] call. */
sealed interface UnequipMountGearResult {
    data class Unequipped(val instance: ItemInstance.MountGear) : UnequipMountGearResult
    data object SlotEmpty : UnequipMountGearResult
}

enum class EquipMountGearRejection {
    /** No instance with the given id exists. */
    INSTANCE_NOT_FOUND,
    /** Instance exists but isn't MOUNT_GEAR (it's EQUIPMENT, KEY, or future category). */
    NOT_MOUNT_GEAR,
    /** Instance is MOUNT_GEAR but is owned by a different agent. */
    NOT_YOUR_INSTANCE,
    /** No mount with the given id exists. */
    UNKNOWN_MOUNT,
    /** Caller is not standing at the mount's node. */
    NOT_SAME_NODE,
    /** Catalog lookup for the instance's item id returned null (state corruption). */
    UNKNOWN_ITEM,
    /** Requested slot isn't in the item's `mountSlots`. */
    INVALID_SLOT_FOR_ITEM,
    /** Requested slot isn't supported by the mount's [MountDef.gearSlots]. */
    SLOT_NOT_ON_MOUNT,
    /** Caller tried to equip an instance already equipped to a (possibly different) mount slot. */
    ALREADY_EQUIPPED,
    /** Target `(mount, slot)` already holds another instance. */
    SLOT_OCCUPIED,
}
