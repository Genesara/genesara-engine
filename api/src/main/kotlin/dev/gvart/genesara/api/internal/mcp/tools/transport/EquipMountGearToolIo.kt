package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.world.MountSlot
import java.util.UUID

internal data class EquipMountGearResponse(
    val kind: String,
    val instanceId: String,
    val mountId: String,
    val slot: MountSlot,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun equipped(instanceId: UUID, mountId: String, slot: MountSlot): EquipMountGearResponse =
            EquipMountGearResponse(
                kind = "equipped",
                instanceId = instanceId.toString(),
                mountId = mountId,
                slot = slot,
            )

        fun rejected(
            instanceId: String,
            mountId: String,
            slot: MountSlot,
            reason: String,
            detail: String,
        ): EquipMountGearResponse =
            EquipMountGearResponse(
                kind = "rejected",
                instanceId = instanceId,
                mountId = mountId,
                slot = slot,
                reason = reason,
                detail = detail,
            )
    }
}
