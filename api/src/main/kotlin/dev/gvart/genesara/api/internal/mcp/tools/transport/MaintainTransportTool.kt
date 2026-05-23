package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class MaintainTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "maintain_transport",
        description = "Apply a maintenance resource to a same-node transport — a mount today, vehicles " +
            "later. The resource's `maintenance.type` (catalog metadata) must match the transport's " +
            "accepted maintenance type (ANIMAL for mounts, fuel/wear types for future vehicles); the " +
            "match restores `value * quantity` to the transport's maintenance gauge (hunger for ANIMAL). " +
            "Same-node required; NOT owner-gated — any caregiver can feed a mount. Rejected if the " +
            "resource isn't tagged, the type doesn't match, or you don't have enough in inventory.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed transport id `mount:<uuid>`.")
        transport_id: String,
        @ToolParam(required = true, description = "Item id of the maintenance resource (e.g. CORN, HERB).")
        resource: String,
        @ToolParam(required = true, description = "Quantity to consume from your inventory.")
        quantity: Int,
        toolContext: ToolContext,
    ): TransportAckResponse {
        touchActivity(toolContext, activity, "maintain_transport")
        val parsed = PrefixedIds.parseMount(transport_id)
            ?: return TransportAckResponse.rejected(transport_id, "bad_target_id", "id must be mount:<uuid>")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.MaintainTransport(
            agent = agent,
            mount = parsed,
            resource = ItemId(resource),
            quantity = quantity,
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TransportAckResponse.queued(command.commandId, appliesAtTick, PrefixedIds.encodeMount(parsed))
    }
}
