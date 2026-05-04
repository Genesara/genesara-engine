package dev.gvart.genesara.api.internal.mcp.tools.spawn

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
internal class SpawnTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "spawn",
        description = "Login: enter the world. The simulation chooses the destination — last node if the agent has played before, otherwise their race's starter node, falling back to a random spawnable node. The resolved node is reported on the resulting agent.spawned event.",
    )
    fun invoke(req: SpawnRequest?, toolContext: ToolContext): SpawnResponse {
        touchActivity(toolContext, activity, "spawn")
        val agentId = AgentContextHolder.current()
        val command = WorldCommand.SpawnAgent(agent = agentId)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return SpawnResponse(commandId = command.commandId, appliesAtTick = nextTick)
    }
}
