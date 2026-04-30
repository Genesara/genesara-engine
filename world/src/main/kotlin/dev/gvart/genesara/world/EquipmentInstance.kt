package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A single physical instance of an equipment-class item (weapon, armor, tool,
 * crafted artifact). Distinguished from a stack of resources by per-instance
 * state: rarity rolled at craft / loot time, live durability, and a creator
 * signature that persists past the creator's death.
 *
 * Backed by `world.agent_equipment_instances`. Empty in this slice — no slice
 * yet creates instances. The schema and store ship now so the equipment-slot
 * slice and the crafting slice can plug in without their own migrations.
 */
data class EquipmentInstance(
    val instanceId: UUID,
    val agentId: AgentId,
    val itemId: ItemId,
    val rarity: Rarity,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    /** Creator's agent id at craft time. Null for loot drops and pre-genesis items. */
    val creatorAgentId: AgentId?,
    val createdAtTick: Long,
    /**
     * Slot this instance is currently equipped in, or null if it's sitting in
     * the agent's stash. Two-handed weapons only fill [EquipSlot.MAIN_HAND] —
     * the off-hand "occupation" is enforced by the equip reducer reading the
     * item catalog's `twoHanded` flag, not by a second row.
     */
    val equippedInSlot: EquipSlot? = null,
) {
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
