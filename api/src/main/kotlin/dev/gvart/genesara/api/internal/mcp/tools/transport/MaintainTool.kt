package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * Generic maintenance verb. v1 resolves `mount:<uuid>` targets; planned
 * extensions: `item:<uuid>` (equipment repair) and `building:<uuid>`
 * (structure repair) — same verb, same tag-match shape (`Item.maintenance.type`
 * vs target's accepted type).
 */
@Component
internal class MaintainTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "maintain",
        description = "Apply a maintenance resource to a same-node target. Today resolves mounts " +
            "(`mount:<uuid>`); equipment and buildings will route through the same verb later. " +
            "The resource's `maintenance.type` (catalog metadata) must match the target's accepted " +
            "maintenance type (ANIMAL for mounts; fuel/wear/HP types later); on match, " +
            "`value * quantity` is restored to the target's maintenance gauge (hunger for ANIMAL " +
            "mounts). Same-node required. Rejected if the resource isn't tagged, the type doesn't " +
            "match, or you don't have enough in inventory.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed target id. Today: `mount:<uuid>`.")
        target_id: String,
        @ToolParam(required = true, description = "Item id of the maintenance resource (e.g. CORN, HERB).")
        resource: String,
        @ToolParam(required = true, description = "Quantity to consume from your inventory.")
        quantity: Int,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "maintain")
        val parsed = PrefixedIds.parseMount(target_id)
            ?: return CommandAckResponse.rejected(target_id, "bad_target_id", "v1 only supports mount:<uuid>")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.Maintain(
            agent = agent,
            target = parsed,
            resource = ItemId(resource),
            quantity = quantity,
        )
        return world.submitQueued(command, engine, target = PrefixedIds.encodeMount(parsed))
    }
}
