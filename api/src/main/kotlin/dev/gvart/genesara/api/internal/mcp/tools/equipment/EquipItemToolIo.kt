package dev.gvart.genesara.api.internal.mcp.tools.equipment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Equip an owned equipment instance into one of the 12 slots: HELMET, CHEST, PANTS, BOOTS, GLOVES, " +
        "AMULET, RING_LEFT, RING_RIGHT, BRACELET_LEFT, BRACELET_RIGHT, MAIN_HAND, OFF_HAND. " +
        "The instance must be owned by the calling agent. Two-handed weapons go to MAIN_HAND and " +
        "block OFF_HAND while equipped.",
)
data class EquipItemRequest(
    @JsonPropertyDescription("Equipment instance UUID (from get_equipment / your event stream).")
    val instanceId: String?,
    @JsonPropertyDescription("Target slot id (e.g. MAIN_HAND, HELMET, RING_LEFT).")
    val slot: String?,
)

/**
 * Either:
 *  - `kind = "equipped"`: success; the instance is now in the named slot.
 *  - `kind = "rejected"`: validation failed; `reason` carries an enum-string code
 *    (`instance_not_found`, `not_your_instance`, `unknown_item`, `not_equipment`,
 *    `invalid_slot_for_item`, `two_handed_not_main_hand`, `already_equipped`,
 *    `off_hand_occupied`, `off_hand_blocked_by_two_handed`, `slot_occupied`,
 *    `bad_request`).
 */
data class EquipItemResponse(
    val kind: String,
    val instanceId: String?,
    val slot: String?,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun equipped(instanceId: UUID, slot: String) =
            EquipItemResponse("equipped", instanceId.toString(), slot)

        fun rejected(instanceId: String?, slot: String?, reason: String, detail: String) =
            EquipItemResponse("rejected", instanceId, slot, reason, detail)
    }
}
