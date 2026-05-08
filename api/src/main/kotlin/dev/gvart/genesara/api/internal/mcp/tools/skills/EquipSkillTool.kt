package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.player.AgentRegistry
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
    private val agents: AgentRegistry,
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
        // Pre-check unknown skill so the rejection names the right cause (the registry
        // would otherwise collapse it to SkillNotDiscovered). Catalog stays hidden.
        val skillDef = catalog.byId(skill) ?: return EquipSkillResponse.rejected(
            skillId = skillId,
            slotIndex = slotIndex,
            reason = "unknown_skill",
            detail = "Skill id '$skillId' is not in the catalog.",
        )

        skillDef.classLock?.let { requiredClass ->
            val agentRow = agents.find(agent) ?: return EquipSkillResponse.rejected(
                skillId = skillId,
                slotIndex = slotIndex,
                reason = "unknown_agent",
                detail = "Agent ${agent.id} is not in the registry.",
            )
            // Surface SkillNotDiscovered (not a dedicated rejection) so the catalog stays
            // hidden from non-eligible classes.
            if (agentRow.classId != requiredClass) {
                return EquipSkillResponse.rejected(
                    skillId = skillId,
                    slotIndex = slotIndex,
                    reason = "skill_not_discovered",
                    detail = "$skillId hasn't been recommended yet. Skills must be discovered via " +
                        "SkillRecommended events before they can be slotted.",
                )
            }
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
