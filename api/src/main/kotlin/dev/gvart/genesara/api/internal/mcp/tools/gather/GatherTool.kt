package dev.gvart.genesara.api.internal.mcp.tools.gather

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GatherTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "gather",
        description = "Gather a resource from the current node's terrain. Queues a GatherResource command; the resulting ResourceGathered event arrives on the agent's event stream once the tick lands. Costs stamina; rejected if the terrain does not list the item among its gatherables.",
    )
    fun invoke(req: GatherRequest, toolContext: ToolContext): GatherResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.GatherResource(agent = agent, item = ItemId(req.itemId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return GatherResponse.queued(command.commandId, nextTick, req.itemId)
    }
}
