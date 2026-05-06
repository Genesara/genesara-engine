package dev.gvart.genesara.api.internal.mcp.tools.equipment

data class GetEquipmentResponse(
    /** Slot id → instance currently in that slot. Slots with no item are absent. */
    val equipped: Map<String, EquipmentInstanceView>,
    /** Equipment instances owned by the agent that are not currently slotted. */
    val stash: List<EquipmentInstanceView>,
)

data class EquipmentInstanceView(
    val instanceId: String,
    val itemId: String,
    val rarity: String,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    /** Slot id if the instance is currently equipped, null otherwise. */
    val equippedInSlot: String? = null,
    /** Creator's agent id (UUID string), null for loot drops. */
    val creatorAgentId: String? = null,
)
