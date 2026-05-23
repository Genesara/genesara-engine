package dev.gvart.genesara.api.internal.mcp.tools.move

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.CoreCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class MoveTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {
    @Tool(name = "move", description = "Move agent to the given adjacent node")
    fun invoke(
        @ToolParam(required = true, description = "Target node id (must be adjacent to the agent's current node).")
        nodeId: Long,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "move")
        val agent = AgentContextHolder.current()
        val command = CoreCommand.MoveAgent(agent = agent, to = NodeId(nodeId))
        return world.submitQueued(command, engine, target = nodeId.toString())
    }
}
