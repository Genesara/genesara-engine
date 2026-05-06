package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.world.EquipSlot
import java.util.UUID

/**
 * Either:
 *  - `kind = "equipped"`: success; the instance is now in the named slot.
 *  - `kind = "rejected"`: validation failed; `reason` carries an enum-string code
 *    (`instance_not_found`, `not_your_instance`, `unknown_item`, `not_equipment`,
 *    `invalid_slot_for_item`, `two_handed_not_main_hand`, `already_equipped`,
 *    `off_hand_occupied`, `off_hand_blocked_by_two_handed`, `slot_occupied`).
 */
data class EquipItemResponse(
    val kind: String,
    val instanceId: UUID,
    val slot: EquipSlot,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun equipped(instanceId: UUID, slot: EquipSlot) =
            EquipItemResponse("equipped", instanceId, slot)

        fun rejected(instanceId: UUID, slot: EquipSlot, reason: String, detail: String) =
            EquipItemResponse("rejected", instanceId, slot, reason, detail)
    }
}
