package dev.gvart.genesara.api.internal.mcp.tools.inventory

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.ItemId
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
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_inventory",
        description = "Return the agent's stackable inventory entries (itemId + quantity + catalog rarity). Read-only — no command queued.",
    )
    fun invoke(req: GetInventoryRequest, toolContext: ToolContext): GetInventoryResponse {
        touchActivity(toolContext, activity, "get_inventory")
        val agentId = AgentContextHolder.current()
        // TODO(equipment-slot): merge per-instance equipment from EquipmentInstanceStore
        //   into the response (separate "instances" list or extended entries) once the
        //   equipment-slot slice ships and the store starts populating.
        val view = world.inventoryOf(agentId)
        return GetInventoryResponse(
            entries = view.entries.map { entry ->
                InventoryEntryView(
                    itemId = entry.itemId.value,
                    quantity = entry.quantity,
                    rarity = rarityFor(entry.itemId).name,
                )
            }
        )
    }

    /**
     * Falls back to [Rarity.COMMON] when the catalog has no entry for [itemId]:
     * `agent_inventory` references the catalog implicitly but jOOQ doesn't enforce a FK
     * (catalog is YAML-driven), so a stale row should project robustly rather than
     * throw.
     */
    private fun rarityFor(itemId: ItemId): Rarity = items.byId(itemId)?.rarity ?: Rarity.COMMON
}
