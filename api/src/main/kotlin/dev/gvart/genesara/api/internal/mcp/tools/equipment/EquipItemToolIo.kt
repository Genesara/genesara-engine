package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.world.EquipSlot
import java.util.UUID

data class EquipItemResponse(
    val kind: String,
    val instanceId: String,
    val slot: EquipSlot,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun equipped(instanceId: UUID, slot: EquipSlot) =
            EquipItemResponse("equipped", instanceId.toString(), slot)

        fun rejected(instanceId: String, slot: EquipSlot, reason: String, detail: String) =
            EquipItemResponse("rejected", instanceId, slot, reason, detail)
    }
}
