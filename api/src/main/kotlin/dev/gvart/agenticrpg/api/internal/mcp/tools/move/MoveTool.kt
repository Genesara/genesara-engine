package dev.gvart.agenticrpg.api.internal.mcp.tools.move

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.api.internal.mcp.presence.touchActivity
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class MoveTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {
    @Tool(name = "move", description = "Move agent to the given adjacent node")
    fun invoke(req: MoveRequest, toolContext: ToolContext): MoveResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.MoveAgent(agent = agent, to = NodeId(req.nodeId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return MoveResponse(commandId = command.commandId, appliesAtTick = nextTick)
    }
}
