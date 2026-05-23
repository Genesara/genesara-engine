package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.MountCargoResult
import dev.gvart.genesara.world.MountCargoService
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class TakeFromMountTool(
    private val cargo: MountCargoService,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "take_from_mount",
        description = "Remove cargo from a same-node mount. No ownership — anyone can take from any mount. Supply EITHER " +
            "`item_id` + `quantity` (stackable RESOURCE from mount cargo back into your inventory) OR " +
            "`instance_id` (per-instance item stowed on the mount back to your stash). Rejected when " +
            "supplying both or neither, or when the mount carries fewer of the requested " +
            "stack / the instance isn't stowed on this mount. Sync — the response is the result.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed mount id, `mount:<uuid>`.")
        transportId: String,
        @ToolParam(required = false, description = "Item id for the stackable path.")
        itemId: String?,
        @ToolParam(required = false, description = "Quantity for the stackable path. Must be > 0.")
        quantity: Int?,
        @ToolParam(required = false, description = "Instance UUID for the per-instance path.")
        instanceId: String?,
        toolContext: ToolContext,
    ): TakeFromMountResponse {
        touchActivity(toolContext, activity, "take_from_mount")
        val mountId = PrefixedIds.parseMount(transportId)
            ?: return TakeFromMountResponse.rejected(
                transportId = transportId,
                reason = "bad_transport_id",
                detail = "transport_id must be mount:<uuid>",
            )
        val agent = AgentContextHolder.current()

        val hasResource = itemId != null && quantity != null
        val hasInstance = instanceId != null
        if (hasResource == hasInstance) {
            return TakeFromMountResponse.rejected(
                transportId = transportId,
                reason = "bad_payload",
                detail = "supply either {item_id,quantity} or {instance_id}, not both or neither",
                itemId = itemId,
                quantity = quantity,
                instanceId = instanceId,
            )
        }

        if (hasResource) {
            return when (val result = cargo.takeResource(agent, mountId, ItemId(itemId), quantity)) {
                is MountCargoResult.Taken -> TakeFromMountResponse.takenResource(transportId, itemId, quantity)
                is MountCargoResult.Rejected -> TakeFromMountResponse.rejected(
                    transportId = transportId,
                    reason = result.reason.toReasonCode(),
                    detail = result.detail,
                    itemId = itemId,
                    quantity = quantity,
                )
                is MountCargoResult.Stored -> TakeFromMountResponse.rejected(
                    transportId = transportId,
                    reason = "internal_error",
                    detail = "service returned Stored on a take call",
                )
            }
        }

        val parsedInstance = runCatching { UUID.fromString(instanceId) }.getOrNull()
            ?: return TakeFromMountResponse.rejected(
                transportId = transportId,
                reason = "bad_instance_id",
                detail = "instance_id must be a UUID",
                instanceId = instanceId,
            )
        return when (val result = cargo.takeInstance(agent, mountId, parsedInstance)) {
            is MountCargoResult.Taken -> TakeFromMountResponse.takenInstance(transportId, parsedInstance.toString())
            is MountCargoResult.Rejected -> TakeFromMountResponse.rejected(
                transportId = transportId,
                reason = result.reason.toReasonCode(),
                detail = result.detail,
                instanceId = parsedInstance.toString(),
            )
            is MountCargoResult.Stored -> TakeFromMountResponse.rejected(
                transportId = transportId,
                reason = "internal_error",
                detail = "service returned Stored on a take call",
            )
        }
    }
}
