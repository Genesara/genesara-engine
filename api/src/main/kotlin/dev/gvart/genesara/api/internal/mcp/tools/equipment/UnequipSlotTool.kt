package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.UnequipResult
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class UnequipSlotTool(
    private val equipment: EquipmentService,
    private val activity: AgentActivityTracker,
    private val derivedPools: DerivedPoolsRefresher,
) {

    @Tool(
        name = "unequip_slot",
        description = "Empty an equipment slot. The instance returns to your stash. " +
            "Sync — no command queued. If the slot was already empty, kind=\"empty\".",
    )
    fun invoke(
        @ToolParam(required = true, description = "Equipment slot to clear (e.g. MAIN_HAND, HELMET, RING_LEFT).")
        slot: EquipSlot,
        toolContext: ToolContext,
    ): UnequipSlotResponse {
        touchActivity(toolContext, activity, "unequip_slot")
        val agent = AgentContextHolder.current()

        return when (val result = equipment.unequip(agent, slot)) {
            is UnequipResult.Unequipped -> {
                derivedPools.refresh(agent)
                UnequipSlotResponse(
                    kind = "unequipped",
                    slot = slot,
                    instanceId = result.instance.instanceId,
                )
            }
            is UnequipResult.SlotEmpty -> UnequipSlotResponse(
                kind = "empty",
                slot = slot,
            )
        }
    }
}
