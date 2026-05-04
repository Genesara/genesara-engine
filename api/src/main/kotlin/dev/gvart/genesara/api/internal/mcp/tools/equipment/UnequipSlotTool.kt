package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.UnequipResult
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class UnequipSlotTool(
    private val equipment: EquipmentService,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "unequip_slot",
        description = "Empty an equipment slot. The instance returns to your stash. " +
            "Sync — no command queued. If the slot was already empty, kind=\"empty\".",
    )
    fun invoke(req: UnequipSlotRequest, toolContext: ToolContext): UnequipSlotResponse {
        touchActivity(toolContext, activity, "unequip_slot")
        val agent = AgentContextHolder.current()

        return when (val result = equipment.unequip(agent, req.slot)) {
            is UnequipResult.Unequipped -> UnequipSlotResponse(
                kind = "unequipped",
                slot = req.slot,
                instanceId = result.instance.instanceId,
            )
            is UnequipResult.SlotEmpty -> UnequipSlotResponse(
                kind = "empty",
                slot = req.slot,
            )
        }
    }
}
