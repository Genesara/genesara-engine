package dev.gvart.genesara.api.internal.mcp.tools.inventory

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetInventoryTool(
    private val world: WorldQueryGateway,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "get_inventory",
        description = "Return the agent's stackable inventory entries (itemId + quantity). Read-only — no command queued.",
    )
    fun invoke(req: GetInventoryRequest, toolContext: ToolContext): GetInventoryResponse {
        touchActivity(toolContext, activity)
        val agentId = AgentContextHolder.current()
        val view = world.inventoryOf(agentId)
        return GetInventoryResponse(
            entries = view.entries.map {
                InventoryEntryView(itemId = it.itemId.value, quantity = it.quantity)
            }
        )
    }
}
