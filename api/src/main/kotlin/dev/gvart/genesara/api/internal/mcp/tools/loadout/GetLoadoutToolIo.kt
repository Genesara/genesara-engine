package dev.gvart.genesara.api.internal.mcp.tools.loadout

import dev.gvart.genesara.world.Rarity

data class GetLoadoutResponse(
    /** Stackable carried items (food, materials, ammo, ground-pickup-able). */
    val stackable: List<InventoryEntryView>,
    val equipment: EquipmentView,
)

data class InventoryEntryView(
    val itemId: String,
    val quantity: Int,
    val rarity: Rarity,
)

data class EquipmentView(
    /** One entry per defined equipment slot, in stable enum order. */
    val slots: List<EquipmentSlotView>,
    /** Per-instance gear the agent owns but has not slotted. */
    val stash: List<EquipmentInstanceView>,
)

data class EquipmentSlotView(
    val slotId: String,
    /** null when this slot is empty. */
    val instance: EquipmentInstanceView?,
)

data class EquipmentInstanceView(
    val instanceId: String,
    val itemId: String,
    val rarity: Rarity,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    /** Creator's agent id (UUID string), null for loot drops. */
    val creatorAgentId: String? = null,
)
