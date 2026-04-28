package dev.gvart.agenticrpg.api.internal.mcp.tools.unspawn

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.api.internal.mcp.presence.touchActivity
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class UnspawnTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "unspawn",
        description = "Logout: leave the world. Position is remembered for the next spawn.",
    )
    fun invoke(req: UnspawnRequest, toolContext: ToolContext): UnspawnResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.UnspawnAgent(agent = agent)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        activity.forget(agent)
        return UnspawnResponse(command.commandId, nextTick)
    }
}
