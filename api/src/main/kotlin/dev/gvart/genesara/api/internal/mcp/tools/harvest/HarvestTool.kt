package dev.gvart.genesara.api.internal.mcp.tools.harvest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ResourceItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class HarvestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "harvest",
        description = "Extract one of the resources currently present at the agent's node. " +
            "Resource availability is per-node and rolled at world generation — call `look_around` first " +
            "and pick an itemId from `current.resources`; harvesting an item the node does not stock is " +
            "rejected with ResourceNotAvailableHere. Queues a Harvest command; the resulting " +
            "ResourceHarvested event arrives on the agent's event stream once the tick lands. Costs stamina.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Resource id available at the agent's current node. Read it from " +
                "`look_around().current.resources` — do not guess. Harvesting an item the node " +
                "does not stock is rejected with ResourceNotAvailableHere.",
        )
        itemId: ResourceItemId,
        toolContext: ToolContext,
    ): HarvestResponse {
        touchActivity(toolContext, activity, "harvest")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Harvest(agent = agent, item = itemId.toItemId())
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return HarvestResponse.queued(command.commandId, nextTick, itemId.name)
    }
}
