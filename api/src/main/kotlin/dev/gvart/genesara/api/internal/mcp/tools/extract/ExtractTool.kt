package dev.gvart.genesara.api.internal.mcp.tools.extract

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ResourceItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class ExtractTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "extract",
        description = "Pull one yield of an extraction-only resource (COAL, ORE, GOLD) from the agent's " +
            "current node. Requires an ACTIVE MINE building on the node. Mirrors `harvest` but for " +
            "items the bare gather verb rejects. Queues an Extract command; ResourceExtracted lands " +
            "on the event stream once the tick applies. Costs stamina; trains MINING.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Extraction-only resource id (COAL, ORE, GOLD). Items not flagged extractionOnly " +
                "are rejected with ResourceNotAvailableHere — use the `harvest` verb instead.",
        )
        itemId: ResourceItemId,
        toolContext: ToolContext,
    ): ExtractResponse {
        touchActivity(toolContext, activity, "extract")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Extract(agent = agent, item = itemId.toItemId())
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return ExtractResponse.queued(command.commandId, appliesAtTick, itemId.name)
    }
}
