package dev.gvart.genesara.api.internal.mcp.tools.mine

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
internal class MineTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "mine",
        description = "Extract a mining-skill resource (stone, ore, coal, gem, salt, clay, peat, sand) " +
            "from the current node. Queues a MineResource command; the resulting ResourceGathered event " +
            "arrives on the agent's event stream once the tick lands. Costs stamina; rejected if the item " +
            "is not a mining-skill resource (use `gather` instead) or if the terrain has no deposit.",
    )
    fun invoke(req: MineRequest, toolContext: ToolContext): MineResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.MineResource(agent = agent, item = ItemId(req.itemId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return MineResponse.queued(command.commandId, nextTick, req.itemId)
    }
}
