package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
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
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_skills",
        description = "Return the agent's DISCOVERED skills only — skills you've been recommended for, are slotted, or have accrued XP in. The full catalog is hidden by design: skills are discovered through gameplay via SkillRecommended events. A freshly-registered agent sees an empty list. slotCount and slotsFilled describe slot capacity regardless.",
    )
    fun invoke(req: GetSkillsRequest, toolContext: ToolContext): GetSkillsResponse {
        touchActivity(toolContext, activity, "get_skills")
        val agent = AgentContextHolder.current()
        val snapshot = skills.snapshot(agent)

        // Snapshot already filters to discovered skills (recommended / slotted /
        // has XP) — see AgentSkillsRegistry.snapshot KDoc. We just enrich each entry
        // with display metadata from the catalog. Skills missing from the catalog
        // (orphan rows from a removed yaml entry) are dropped.
        val views = snapshot.perSkill.values
            .mapNotNull { state ->
                val skill = catalog.byId(state.skill) ?: return@mapNotNull null
                SkillView(
                    id = skill.id.value,
                    displayName = skill.displayName,
                    category = skill.category.name,
                    xp = state.xp,
                    level = state.level,
                    slotIndex = state.slotIndex,
                    recommendCount = state.recommendCount,
                )
            }
            .sortedBy { it.id }

        return GetSkillsResponse(
            slotCount = snapshot.slotCount,
            slotsFilled = snapshot.slotsFilled,
            skills = views,
        )
    }
}
