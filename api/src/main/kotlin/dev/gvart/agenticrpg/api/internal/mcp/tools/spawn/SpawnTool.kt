package dev.gvart.agenticrpg.api.internal.mcp.tools.spawn

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.api.internal.mcp.presence.touchActivity
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.WorldQueryGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class SpawnTool(
    private val world: WorldCommandGateway,
    private val query: WorldQueryGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "spawn",
        description = "Login: enter the world. Resumes at the last node if the agent has played before, otherwise places them at a random node. No-op if already in the world.",
    )
    fun invoke(req: SpawnRequest, toolContext: ToolContext): SpawnResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()

        query.activePositionOf(agent)?.let { return SpawnResponse.alreadyPresent(it.value) }

        val target = query.locationOf(agent)
            ?: query.randomSpawnableNode()
            ?: error("World has no spawnable nodes")
        val command = WorldCommand.SpawnAgent(agent = agent, at = target)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return SpawnResponse.queued(command.commandId, nextTick, target.value)
    }
}
