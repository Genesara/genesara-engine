package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.world.EquipMountGearRejection
import dev.gvart.genesara.world.EquipMountGearResult
import dev.gvart.genesara.world.EquipMountGearService
import dev.gvart.genesara.world.MountSlot
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class EquipMountGearTool(
    private val gear: EquipMountGearService,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "equip_transport_gear",
        description = "Equip a MOUNT_GEAR instance (SADDLE / BARDING / HARNESS) onto a same-node " +
            "transport. The gear instance must be yours (you carry it in your stash); the mount " +
            "itself has no per-agent ownership. The slot must be supported by the mount type and " +
            "by the gear item. Sync — no command queued; the response is the result.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Mount-gear instance UUID (from get_loadout / your event stream).")
        instanceId: String,
        @ToolParam(required = true, description = "Wire-prefixed mount id — must be `mount:<uuid>` as returned by look_around / inspect.")
        transportId: String,
        @ToolParam(required = true, description = "Mount slot to fill (SADDLE, BARDING, or HARNESS).")
        slot: MountSlot,
        toolContext: ToolContext,
    ): EquipMountGearResponse {
        touchActivity(toolContext, activity, "equip_transport_gear")
        val instanceUuid = runCatching { UUID.fromString(instanceId) }.getOrNull()
            ?: return EquipMountGearResponse.rejected(
                instanceId = instanceId,
                mountId = transportId,
                slot = slot,
                reason = "bad_instance_id",
                detail = "instanceId must be a UUID",
            )
        val mountId = PrefixedIds.parseMount(transportId)
            ?: return EquipMountGearResponse.rejected(
                instanceId = instanceUuid.toString(),
                mountId = transportId,
                slot = slot,
                reason = "bad_transport_id",
                detail = "transportId must be mount:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val encodedMount = PrefixedIds.encodeMount(mountId)

        return when (val result = gear.equipMountGear(agent, instanceUuid, mountId, slot)) {
            is EquipMountGearResult.Equipped -> EquipMountGearResponse.equipped(
                instanceId = result.instance.instanceId,
                mountId = encodedMount,
                slot = slot,
            )
            is EquipMountGearResult.Rejected -> EquipMountGearResponse.rejected(
                instanceId = instanceUuid.toString(),
                mountId = encodedMount,
                slot = slot,
                reason = result.reason.toReasonCode(),
                detail = result.detail ?: result.reason.detailFor(slot),
            )
        }
    }

    private fun EquipMountGearRejection.toReasonCode(): String = name.lowercase()

    private fun EquipMountGearRejection.detailFor(slot: MountSlot): String = when (this) {
        EquipMountGearRejection.INSTANCE_NOT_FOUND -> "no item instance with that id"
        EquipMountGearRejection.NOT_MOUNT_GEAR -> "that instance is not mount gear"
        EquipMountGearRejection.NOT_YOUR_INSTANCE -> "that instance belongs to a different agent"
        EquipMountGearRejection.UNKNOWN_MOUNT -> "no mount with that id"
        EquipMountGearRejection.NOT_SAME_NODE -> "you must be at the mount's node to equip its gear"
        EquipMountGearRejection.UNKNOWN_ITEM -> "instance references an unknown item id (catalog drift)"
        EquipMountGearRejection.INVALID_SLOT_FOR_ITEM -> "this gear cannot occupy ${slot.name}"
        EquipMountGearRejection.SLOT_NOT_ON_MOUNT -> "this mount has no ${slot.name} slot"
        EquipMountGearRejection.ALREADY_EQUIPPED -> "instance is already equipped — unequip it first"
        EquipMountGearRejection.SLOT_OCCUPIED -> "${slot.name} already holds another instance"
    }
}
