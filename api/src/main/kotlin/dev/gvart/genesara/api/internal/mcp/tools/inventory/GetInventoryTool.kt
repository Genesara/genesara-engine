package dev.gvart.genesara.api.internal.mcp.tools.inventory

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetInventoryTool(
    private val world: WorldQueryGateway,
    private val items: ItemLookup,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "get_inventory",
        description = "Return the agent's stackable inventory entries (itemId + quantity + catalog rarity). Read-only — no command queued.",
    )
    fun invoke(req: GetInventoryRequest, toolContext: ToolContext): GetInventoryResponse {
        touchActivity(toolContext, activity)
        val agentId = AgentContextHolder.current()
        // TODO(equipment-slot): merge per-instance equipment from EquipmentInstanceStore
        //   into the response (separate "instances" list or extended entries) once the
        //   equipment-slot slice ships and the store starts populating.
        val view = world.inventoryOf(agentId)
        return GetInventoryResponse(
            entries = view.entries.map { entry ->
                // Catalog miss is unexpected — agent_inventory PK references the catalog
                // implicitly, but jOOQ doesn't enforce it (no FK to a catalog table since
                // it's YAML-driven). Falling back to COMMON keeps the projection robust
                // against catalog reshuffles instead of throwing on a stale row.
                val rarity = items.byId(entry.itemId)?.rarity ?: Rarity.COMMON
                InventoryEntryView(
                    itemId = entry.itemId.value,
                    quantity = entry.quantity,
                    rarity = rarity.name,
                )
            }
        )
    }
}
