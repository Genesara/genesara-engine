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
internal class StoreOnMountTool(
    private val cargo: MountCargoService,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "store_on_mount",
        description = "Move cargo onto a same-node mount. No ownership — anyone can load any mount. Supply EITHER " +
            "`item_id` + `quantity` (stackable RESOURCE from your inventory) OR `instance_id` " +
            "(per-instance item from your stash — EQUIPMENT, KEY, MOUNT_GEAR not currently equipped). " +
            "Rejected when supplying both or neither. Carry capacity = mount's base cap + any " +
            "HARNESS bonus; the request is rejected when the addition would push total cargo " +
            "weight (stackables + stowed instances) above the cap. Sync — the response is the result.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed mount id, `mount:<uuid>`.")
        transportId: String,
        @ToolParam(required = false, description = "Item id for the stackable path (e.g. WOOD, BERRY).")
        itemId: String?,
        @ToolParam(required = false, description = "Quantity for the stackable path. Must be > 0.")
        quantity: Int?,
        @ToolParam(required = false, description = "Instance UUID for the per-instance path.")
        instanceId: String?,
        toolContext: ToolContext,
    ): StoreOnMountResponse {
        touchActivity(toolContext, activity, "store_on_mount")
        val mountId = PrefixedIds.parseMount(transportId)
            ?: return StoreOnMountResponse.rejected(
                transportId = transportId,
                reason = "bad_transport_id",
                detail = "transport_id must be mount:<uuid>",
            )
        val agent = AgentContextHolder.current()

        val hasResource = itemId != null && quantity != null
        val hasInstance = instanceId != null
        if (hasResource == hasInstance) {
            return StoreOnMountResponse.rejected(
                transportId = transportId,
                reason = "bad_payload",
                detail = "supply either {item_id,quantity} or {instance_id}, not both or neither",
                itemId = itemId,
                quantity = quantity,
                instanceId = instanceId,
            )
        }

        if (hasResource) {
            return when (val result = cargo.storeResource(agent, mountId, ItemId(itemId), quantity)) {
                is MountCargoResult.Stored -> StoreOnMountResponse.storedResource(transportId, itemId, quantity)
                is MountCargoResult.Rejected -> StoreOnMountResponse.rejected(
                    transportId = transportId,
                    reason = result.reason.toReasonCode(),
                    detail = result.detail,
                    itemId = itemId,
                    quantity = quantity,
                )
                is MountCargoResult.Taken -> StoreOnMountResponse.rejected(
                    transportId = transportId,
                    reason = "internal_error",
                    detail = "service returned Taken on a store call",
                )
            }
        }

        val parsedInstance = runCatching { UUID.fromString(instanceId) }.getOrNull()
            ?: return StoreOnMountResponse.rejected(
                transportId = transportId,
                reason = "bad_instance_id",
                detail = "instance_id must be a UUID",
                instanceId = instanceId,
            )
        return when (val result = cargo.storeInstance(agent, mountId, parsedInstance)) {
            is MountCargoResult.Stored -> StoreOnMountResponse.storedInstance(transportId, parsedInstance.toString())
            is MountCargoResult.Rejected -> StoreOnMountResponse.rejected(
                transportId = transportId,
                reason = result.reason.toReasonCode(),
                detail = result.detail,
                instanceId = parsedInstance.toString(),
            )
            is MountCargoResult.Taken -> StoreOnMountResponse.rejected(
                transportId = transportId,
                reason = "internal_error",
                detail = "service returned Taken on a store call",
            )
        }
    }
}
