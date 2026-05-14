package dev.gvart.genesara.api.internal.mcp.tools.loadout

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.world.Rarity

data class GetLoadoutResponse(
    /** Pure stackable inventory (one row per (itemId) with quantity). */
    val stackable: List<InventoryEntryView>,
    /** Per-instance carried items that are NOT equipment — today: keys. */
    val instances: List<ItemInstanceView>,
    val equipment: EquipmentView,
)

data class InventoryEntryView(
    val itemId: String,
    val quantity: Int,
    val rarity: Rarity,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ItemInstanceView(
    val instanceId: String,
    val itemId: String,
    val category: String,
    val rarity: Rarity,
    /** For KEY instances: the building instance id of the gate this key opens. */
    val gateInstanceId: String? = null,
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
