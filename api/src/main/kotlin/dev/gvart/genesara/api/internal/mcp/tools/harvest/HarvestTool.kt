package dev.gvart.genesara.api.internal.mcp.tools.harvest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class HarvestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "harvest",
        description = "Extract a resource (wood, berries, herbs, stone, ore, coal, gem, salt, clay, peat, sand, …) " +
            "from the current node. Queues a Harvest command; the resulting ResourceHarvested event arrives on " +
            "the agent's event stream once the tick lands. Costs stamina; rejected if the terrain has no deposit " +
            "of the requested item.",
    )
    fun invoke(req: HarvestRequest, toolContext: ToolContext): HarvestResponse {
        touchActivity(toolContext, activity, "harvest")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Harvest(agent = agent, item = ItemId(req.itemId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return HarvestResponse.queued(command.commandId, nextTick, req.itemId)
    }
}
