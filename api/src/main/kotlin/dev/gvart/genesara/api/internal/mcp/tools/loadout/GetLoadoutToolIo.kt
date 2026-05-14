package dev.gvart.genesara.api.internal.mcp.tools.loadout

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.world.Rarity

data class GetLoadoutResponse(
    /**
     * Carried inventory: plain stackables (itemId + quantity + rarity) AND per-instance
     * items (e.g. GATE_KEY) projected as quantity=1 entries carrying their own
     * `instanceId` (and `gateInstanceId` for keys). Equipment lives separately under
     * [equipment].
     */
    val stackable: List<InventoryEntryView>,
    val equipment: EquipmentView,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InventoryEntryView(
    val itemId: String,
    val quantity: Int,
    val rarity: Rarity,
    /** Set for per-instance items (e.g. GATE_KEY); null for plain stackables. */
    val instanceId: String? = null,
    /** For GATE_KEY: the building instance id of the gate this key opens. */
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
