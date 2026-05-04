package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentService
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class EquipItemTool(
    private val equipment: EquipmentService,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "equip_item",
        description = "Equip an owned equipment instance into one of the 12 slots " +
            "(HELMET, CHEST, PANTS, BOOTS, GLOVES, AMULET, RING_LEFT, RING_RIGHT, " +
            "BRACELET_LEFT, BRACELET_RIGHT, MAIN_HAND, OFF_HAND). The instance must " +
            "already be in your stash (use the admin seed endpoint or future " +
            "crafting / loot flows to acquire). Two-handed weapons go to MAIN_HAND " +
            "and lock OFF_HAND. Sync — no command queued; the response is the result.",
    )
    fun invoke(req: EquipItemRequest, toolContext: ToolContext): EquipItemResponse {
        touchActivity(toolContext, activity, "equip_item")
        val agent = AgentContextHolder.current()

        return when (val result = equipment.equip(agent, req.instanceId, req.slot)) {
            is EquipResult.Equipped -> EquipItemResponse.equipped(
                instanceId = result.instance.instanceId,
                slot = req.slot,
            )
            is EquipResult.Rejected -> EquipItemResponse.rejected(
                instanceId = req.instanceId,
                slot = req.slot,
                reason = result.reason.toReasonCode(),
                detail = result.detail ?: result.reason.detailFor(req.slot),
            )
        }
    }

    private fun EquipRejection.toReasonCode(): String = name.lowercase()

    private fun EquipRejection.detailFor(slot: EquipSlot): String = when (this) {
        EquipRejection.INSTANCE_NOT_FOUND -> "no equipment instance with that id"
        EquipRejection.NOT_YOUR_INSTANCE -> "that instance belongs to a different agent"
        EquipRejection.UNKNOWN_ITEM -> "instance references an unknown item id (catalog drift)"
        EquipRejection.NOT_EQUIPMENT -> "item is not equipment-class (no valid slots)"
        EquipRejection.INVALID_SLOT_FOR_ITEM -> "this item cannot occupy ${slot.name}"
        EquipRejection.TWO_HANDED_NOT_MAIN_HAND -> "two-handed weapons can only be equipped to MAIN_HAND"
        EquipRejection.ALREADY_EQUIPPED -> "instance is already in another slot — unequip it first"
        EquipRejection.INSUFFICIENT_ATTRIBUTES -> "you don't meet the item's attribute requirements"
        EquipRejection.INSUFFICIENT_SKILLS -> "you don't meet the item's skill requirements"
        EquipRejection.OFF_HAND_OCCUPIED -> "OFF_HAND must be empty to equip a two-handed weapon"
        EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED -> "MAIN_HAND holds a two-handed weapon; OFF_HAND is locked"
        EquipRejection.SLOT_OCCUPIED -> "${slot.name} already holds another instance"
    }
}
