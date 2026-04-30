package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Equip / unequip operations on the 12-slot equipment grid.
 *
 * Sync, like skill-slot assignment — there's no world-state interaction worth
 * tick-aligning, and inventory management has no race window an agent could
 * exploit by queueing it. Per-agent operations on disjoint slots commute, so
 * a tight transaction at the store layer is sufficient.
 *
 * Ownership, slot validity, two-handed lock, and slot occupancy are validated
 * before the write; on success the [EquipmentInstance] in the returned
 * [EquipResult.Equipped] reflects the post-write row (slot field populated).
 *
 * Stat / skill *requirements* (e.g. "Iron Greatsword needs Strength ≥ 12") are
 * intentionally NOT in this slice — that surface is owned by Slice C2.
 */
interface EquipmentService {

    /**
     * Move [instanceId] (which must be owned by [agentId]) into [slot].
     *
     * Validation order (the rejection priority matters because earlier checks
     * surface clearer corrective signals to the agent):
     *
     *  1. instance exists                          → [EquipRejection.INSTANCE_NOT_FOUND]
     *  2. instance belongs to caller               → [EquipRejection.NOT_YOUR_INSTANCE]
     *  3. item exists in the catalog               → [EquipRejection.UNKNOWN_ITEM]
     *  4. item is equipment with at least one slot → [EquipRejection.NOT_EQUIPMENT]
     *  5. requested slot is valid for the item     → [EquipRejection.INVALID_SLOT_FOR_ITEM]
     *  6. two-handed routes to MAIN_HAND only      → [EquipRejection.TWO_HANDED_NOT_MAIN_HAND]
     *  7. instance not already equipped elsewhere  → [EquipRejection.ALREADY_EQUIPPED]
     *  8. two-handed needs off-hand empty          → [EquipRejection.OFF_HAND_OCCUPIED]
     *  9. off-hand free of two-handed lock         → [EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED]
     * 10. target slot is empty                     → [EquipRejection.SLOT_OCCUPIED]
     */
    fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult

    /**
     * Empty the given slot for [agentId]. The instance returns to the agent's
     * stash. Returns [UnequipResult.SlotEmpty] if nothing was in the slot.
     */
    fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult

    /**
     * Returns the agent's currently-equipped gear keyed by slot. Slots with no
     * equipped item are absent from the map.
     */
    fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance>
}

/** Outcome of an [EquipmentService.equip] call. */
sealed interface EquipResult {
    data class Equipped(val instance: EquipmentInstance) : EquipResult
    data class Rejected(val reason: EquipRejection) : EquipResult
}

/** Outcome of an [EquipmentService.unequip] call. */
sealed interface UnequipResult {
    data class Unequipped(val instance: EquipmentInstance) : UnequipResult
    data object SlotEmpty : UnequipResult
}

enum class EquipRejection {
    /** No instance with the given id exists. */
    INSTANCE_NOT_FOUND,
    /** Instance exists but is owned by a different agent. */
    NOT_YOUR_INSTANCE,
    /** Catalog lookup for the instance's item id returned null (state corruption). */
    UNKNOWN_ITEM,
    /** Item isn't `EQUIPMENT` or has an empty `validSlots` set — nothing to slot. */
    NOT_EQUIPMENT,
    /** Requested slot isn't in the item's `validSlots`. */
    INVALID_SLOT_FOR_ITEM,
    /** Item is two-handed but the requested slot isn't MAIN_HAND. */
    TWO_HANDED_NOT_MAIN_HAND,
    /** Caller tried to equip an instance that's already in a slot — unequip first. */
    ALREADY_EQUIPPED,
    /** Equipping a two-handed weapon to MAIN_HAND while OFF_HAND has an item. */
    OFF_HAND_OCCUPIED,
    /** Equipping anything to OFF_HAND while a two-handed weapon is in MAIN_HAND. */
    OFF_HAND_BLOCKED_BY_TWO_HANDED,
    /** Target slot already holds another instance. */
    SLOT_OCCUPIED,
}
