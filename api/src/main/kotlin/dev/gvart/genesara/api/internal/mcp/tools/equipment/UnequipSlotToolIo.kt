package dev.gvart.genesara.api.internal.mcp.tools.equipment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.world.EquipSlot
import java.util.UUID

@JsonClassDescription(
    "Empty an equipment slot, returning the instance to your stash. " +
        "Returns kind=\"unequipped\" with the freed instance id, or kind=\"empty\" if " +
        "the slot was already empty.",
)
data class UnequipSlotRequest(
    @JsonPropertyDescription("Slot id to clear (e.g. MAIN_HAND, HELMET, RING_LEFT).")
    val slot: EquipSlot,
)

data class UnequipSlotResponse(
    val kind: String,
    val slot: EquipSlot,
    val instanceId: UUID? = null,
)
