package dev.gvart.genesara.api.internal.mcp.tools.equipment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription(
    "Empty an equipment slot, returning the instance to your stash. " +
        "Returns kind=\"unequipped\" with the freed instance id, kind=\"empty\" if " +
        "the slot was already empty, or kind=\"rejected\" with reason=\"bad_request\" " +
        "for a malformed slot id.",
)
data class UnequipSlotRequest(
    @JsonPropertyDescription("Slot id to clear (e.g. MAIN_HAND, HELMET, RING_LEFT).")
    val slot: String?,
)

data class UnequipSlotResponse(
    val kind: String,
    val slot: String?,
    val instanceId: String? = null,
    val reason: String? = null,
    val detail: String? = null,
)
