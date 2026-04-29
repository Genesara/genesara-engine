package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class EquipSkillTool(
    private val skills: AgentSkillsRegistry,
    private val catalog: SkillLookup,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "equip_skill",
        description = "Permanently assign a skill to a slot. IRREVERSIBLE — once placed, the skill stays in that slot for the agent's lifetime. There is no unequip operation. Validates the slot is empty, the skill exists, and the skill is not already in another slot.",
    )
    fun invoke(req: EquipSkillRequest, toolContext: ToolContext): EquipSkillResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()

        val skillId = SkillId(req.skillId)
        if (catalog.byId(skillId) == null) {
            return EquipSkillResponse.rejected(
                skillId = req.skillId,
                slotIndex = req.slotIndex,
                reason = "unknown_skill",
                detail = "Skill id '${req.skillId}' is not in the catalog. Call get_skills for the full list.",
            )
        }

        return when (val err = skills.setSlot(agent, skillId, req.slotIndex)) {
            null -> EquipSkillResponse.ok(req.skillId, req.slotIndex)
            is SkillSlotError.SlotIndexOutOfRange -> EquipSkillResponse.rejected(
                skillId = req.skillId,
                slotIndex = req.slotIndex,
                reason = "slot_index_out_of_range",
                detail = "slotIndex=${err.slotIndex} is outside [0, ${err.slotCount})",
            )
            is SkillSlotError.SlotOccupied -> EquipSkillResponse.rejected(
                skillId = req.skillId,
                slotIndex = req.slotIndex,
                reason = "slot_occupied",
                detail = "slot ${err.slotIndex} already holds ${err.occupiedBy.value} (slots are permanent)",
            )
            is SkillSlotError.SkillAlreadySlotted -> EquipSkillResponse.rejected(
                skillId = req.skillId,
                slotIndex = req.slotIndex,
                reason = "skill_already_slotted",
                detail = "${err.skill.value} is already in slot ${err.existingSlotIndex}",
            )
        }
    }
}
