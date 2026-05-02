package dev.gvart.genesara.api.internal.mcp.tools.drink

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class DrinkTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "drink",
        description = "Drink directly from the agent's current node — works on water-source terrains (coastal, river delta, wetlands, shoreline). Queues a Drink command; the AgentDrank event arrives on the event stream once the tick lands. Rejected on terrains without surface water.",
    )
    fun invoke(req: DrinkRequest, toolContext: ToolContext): DrinkResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Drink(agent = agent)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return DrinkResponse.queued(command.commandId, nextTick)
    }
}
