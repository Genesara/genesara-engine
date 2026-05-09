package dev.gvart.genesara.api.internal.mcp.tools.build

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class BuildTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "build",
        description = "Spend one work step on a building type at the agent's current node. " +
            "First call lays the foundation; subsequent calls advance the same in-progress build until it completes. " +
            "Queues a BuildStructure command; the resulting BuildingPlaced/Progressed/Completed event " +
            "arrives on the agent's event stream once the tick lands. Costs per-step stamina + materials.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Building type to advance one work step at the agent's current node (e.g. CAMPFIRE, WORKBENCH, STORAGE_CHEST).")
        type: BuildingType,
        toolContext: ToolContext,
    ): BuildResponse {
        touchActivity(toolContext, activity, "build")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.BuildStructure(agent = agent, type = type)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return BuildResponse(commandId = command.commandId, appliesAtTick = appliesAtTick, type = type)
    }
}
