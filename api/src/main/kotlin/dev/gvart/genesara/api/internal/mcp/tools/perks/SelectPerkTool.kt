package dev.gvart.genesara.api.internal.mcp.tools.perks

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class SelectPerkTool(
    private val perks: PerkLookup,
    private val agentPerks: AgentPerksRegistry,
    private val skills: AgentSkillsRegistry,
    private val tickClock: TickClock,
    private val publisher: ApplicationEventPublisher,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "select_perk",
        description = "Permanently commit to one perk from a binary fork offered when a slotted skill " +
            "crossed a milestone (50/100/150). IRREVERSIBLE — once chosen, the perk stays for the agent's " +
            "lifetime. Pending offers come from PerkChoiceOffered events and are also surfaced as " +
            "`pendingPerkChoices` on get_status. Validates: perk exists, perk matches the (skill, milestone) " +
            "arguments, the agent's slotted skill level reaches the milestone, and no prior pick exists for " +
            "the same (skill, milestone).",
    )
    fun invoke(
        @ToolParam(required = true, description = "Skill id from the PerkChoiceOffered event (e.g. SWORD).")
        skillId: String,
        @ToolParam(required = true, description = "Milestone level: one of 50, 100, 150.")
        milestone: Int,
        @ToolParam(required = true, description = "Perk id chosen from the offered options (e.g. SWORD_BLEEDER).")
        perkId: String,
        toolContext: ToolContext,
    ): SelectPerkResponse {
        touchActivity(toolContext, activity, "select_perk")
        val agent = AgentContextHolder.current()

        val perk = perks.byId(PerkId(perkId)) ?: return SelectPerkResponse.rejected(
            skillId = skillId,
            milestone = milestone,
            perkId = perkId,
            reason = "unknown_perk",
            detail = "Perk id '$perkId' is not in the catalog.",
        )

        if (perk.skill.value != skillId || perk.milestoneLevel != milestone) {
            return SelectPerkResponse.rejected(
                skillId = skillId,
                milestone = milestone,
                perkId = perkId,
                reason = "perk_mismatch",
                detail = "Perk '$perkId' belongs to ${perk.skill.value}@${perk.milestoneLevel}, " +
                    "not $skillId@$milestone",
            )
        }

        val slottedLevel = skills.slottedSkillLevel(agent, SkillId(skillId))
        if (slottedLevel < milestone) {
            return SelectPerkResponse.rejected(
                skillId = skillId,
                milestone = milestone,
                perkId = perkId,
                reason = "milestone_not_reached",
                detail = "$skillId is at slotted level $slottedLevel; need >= $milestone to commit " +
                    "a milestone-$milestone perk (level 0 means the skill is not slotted).",
            )
        }

        val tick = tickClock.currentTick()
        return when (val outcome = agentPerks.recordChoice(agent, perk.id, tick)) {
            RecordPerkResult.Recorded -> {
                publisher.publishEvent(
                    AgentEvent.PerkChosen(
                        agent = agent,
                        skill = perk.skill,
                        milestone = perk.milestoneLevel,
                        perk = perk.id,
                        tick = tick,
                    ),
                )
                SelectPerkResponse.ok(skillId, milestone, perkId)
            }
            is RecordPerkResult.MilestoneAlreadyChosen -> SelectPerkResponse.rejected(
                skillId = skillId,
                milestone = milestone,
                perkId = perkId,
                reason = "already_chosen",
                detail = "Already picked ${outcome.existing.value} for ${outcome.skill.value}@" +
                    "${outcome.milestoneLevel} (perks are forever).",
            )
            is RecordPerkResult.UnknownPerk -> SelectPerkResponse.rejected(
                skillId = skillId,
                milestone = milestone,
                perkId = perkId,
                reason = "unknown_perk",
                detail = "Perk id '${outcome.perk.value}' is not in the catalog.",
            )
        }
    }
}
