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
import org.springframework.stereotype.Component

@Component
internal class BuildTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "build",
        description = "Spend one work step on a building type id at the agent's current node. " +
            "First call lays the foundation; subsequent calls advance the same in-progress build until it completes. " +
            "Queues a BuildStructure command; the resulting BuildingPlaced/Progressed/Completed event " +
            "arrives on the agent's event stream once the tick lands. Costs per-step stamina + materials.",
    )
    fun invoke(req: BuildRequest, toolContext: ToolContext): BuildResponse {
        touchActivity(toolContext, activity, "build")
        val type = parseType(req.type)
            ?: return BuildResponse.error(
                req.type,
                "Unknown building type: '${req.type}'. Valid types: ${BuildingType.entries.joinToString { it.name }}",
            )
        val agent = AgentContextHolder.current()
        val command = WorldCommand.BuildStructure(agent = agent, type = type)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return BuildResponse.queued(command.commandId, nextTick, type.name)
    }

    private fun parseType(raw: String): BuildingType? =
        runCatching { BuildingType.valueOf(raw.trim().uppercase()) }.getOrNull()
}
