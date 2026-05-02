package dev.gvart.genesara.api.internal.mcp.tools.getmap

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.AgentMapMemoryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetMapTool(
    private val mapMemory: AgentMapMemoryGateway,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_map",
        description = "Return the agent's known map: every node they've had in vision, with " +
            "terrain at last sighting and the tick they first/last saw it. Read-only — no " +
            "command queued. Live state is in look_around / inspect.",
    )
    fun invoke(req: GetMapRequest, toolContext: ToolContext): GetMapResponse {
        touchActivity(toolContext, activity, "get_map")
        val agentId = AgentContextHolder.current()
        val recalled = mapMemory.recall(agentId)
        return GetMapResponse(
            nodes = recalled.map {
                RecalledNodeView(
                    nodeId = it.nodeId.value,
                    regionId = it.regionId.value,
                    q = it.q,
                    r = it.r,
                    terrain = it.terrain.name,
                    biome = it.biome?.name,
                    firstSeenTick = it.firstSeenTick,
                    lastSeenTick = it.lastSeenTick,
                )
            },
        )
    }
}
