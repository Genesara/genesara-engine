package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class EquipSkillTool(
    private val skills: AgentSkillsRegistry,
    private val catalog: SkillLookup,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "equip_skill",
        description = "Permanently assign a skill to a slot. IRREVERSIBLE — once placed, the skill stays in that slot for the agent's lifetime. There is no unequip operation. The skill must already have been recommended to this agent (you've received a SkillRecommended event for it); skills you've never been recommended cannot be slotted.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Skill id from a SkillRecommended event (e.g. FORAGING, MINING).")
        skillId: String,
        @ToolParam(
            required = true,
            description = "Target slot index (0-based). Must be < slotCount from get_skills, and the slot must be empty.",
        )
        slotIndex: Int,
        toolContext: ToolContext,
    ): EquipSkillResponse {
        touchActivity(toolContext, activity, "equip_skill")
        val agent = AgentContextHolder.current()

        val skill = SkillId(skillId)
        // The registry would surface SkillNotDiscovered for an unknown id (a non-existent
        // skill can never have been recommended); we pre-check for "unknown_skill" so the
        // agent's error message is accurate. The catalog stays hidden — discovery is
        // event-driven, not enumerable.
        if (catalog.byId(skill) == null) {
            return EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "unknown_skill",
                detail = "Skill id '$skillId' is not in the catalog.",
            )
        }

        return when (val err = skills.setSlot(agent, skill, slotIndex)) {
            null -> EquipSkillResponse.ok(skillId, slotIndex)
            is SkillSlotError.SlotIndexOutOfRange -> EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "slot_index_out_of_range",
                detail = "slotIndex=${err.slotIndex} is outside [0, ${err.slotCount})",
            )
            is SkillSlotError.SlotOccupied -> EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "slot_occupied",
                detail = "slot ${err.slotIndex} already holds ${err.occupiedBy.value} (slots are permanent)",
            )
            is SkillSlotError.SkillAlreadySlotted -> EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "skill_already_slotted",
                detail = "${err.skill.value} is already in slot ${err.existingSlotIndex}",
            )
            is SkillSlotError.SkillNotDiscovered -> EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "skill_not_discovered",
                detail = "${err.skill.value} hasn't been recommended yet. Skills must be discovered via SkillRecommended events before they can be slotted.",
            )
        }
    }
}
