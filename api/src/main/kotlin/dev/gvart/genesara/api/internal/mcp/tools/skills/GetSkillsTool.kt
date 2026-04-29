package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillLookup
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetSkillsTool(
    private val skills: AgentSkillsRegistry,
    private val catalog: SkillLookup,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "get_skills",
        description = "Return the agent's full skill catalog with current XP, derived level, slot assignment, and recommend count per skill. Slots are PERMANENT once assigned — see equip_skill.",
    )
    fun invoke(req: GetSkillsRequest, toolContext: ToolContext): GetSkillsResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val snapshot = skills.snapshot(agent)

        val views = catalog.all().map { skill ->
            val state = snapshot.perSkill[skill.id]
            SkillView(
                id = skill.id.value,
                displayName = skill.displayName,
                category = skill.category.name,
                xp = state?.xp ?: 0,
                level = state?.level ?: 0,
                slotIndex = state?.slotIndex,
                recommendCount = state?.recommendCount ?: 0,
            )
        }

        return GetSkillsResponse(
            slotCount = snapshot.slotCount,
            slotsFilled = snapshot.slotsFilled,
            skills = views,
        )
    }
}
