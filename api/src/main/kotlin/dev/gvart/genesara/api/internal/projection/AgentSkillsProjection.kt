package dev.gvart.genesara.api.internal.projection

import dev.gvart.genesara.api.internal.mcp.tools.getstatus.ChosenPerkView
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.PendingPerkChoiceView
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.SkillEntryView
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.SkillSlotView
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.SkillsView
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import org.springframework.stereotype.Component

/** Shared by `get_status` and `/api/agents/{id}/skills` so the two channels can't drift. */
@Component
internal class AgentSkillsProjection(
    private val skillsRegistry: AgentSkillsRegistry,
    private val skillCatalog: SkillLookup,
    private val perksRegistry: AgentPerksRegistry,
    private val perkCatalog: PerkLookup,
) {

    fun project(agentId: AgentId): SkillsView {
        val snapshot = skillsRegistry.snapshot(agentId)
        val resolved = snapshot.perSkill.values.mapNotNull { state ->
            val skill = skillCatalog.byId(state.skill) ?: return@mapNotNull null
            state to SkillEntryView(
                id = skill.id.value,
                displayName = skill.displayName,
                category = skill.category,
                xp = state.xp,
                level = state.level,
                recommendCount = state.recommendCount,
            )
        }
        val bySlot = resolved
            .filter { (state, _) -> state.slotIndex != null }
            .associate { (state, view) -> state.slotIndex!! to view }
        val slots = (0 until snapshot.slotCount).map { idx ->
            SkillSlotView(slotIndex = idx, skill = bySlot[idx])
        }
        val unslotted = resolved
            .filter { (state, _) -> state.slotIndex == null }
            .map { (_, view) -> view }
            .sortedBy { it.id }
        val chosenPerks = perksRegistry.snapshot(agentId).perks
            .map {
                ChosenPerkView(
                    skillId = it.skill.value,
                    milestone = it.milestoneLevel,
                    perkId = it.perkId.value,
                )
            }
            .sortedWith(compareBy({ it.skillId }, { it.milestone }))
        return SkillsView(
            slotCount = snapshot.slotCount,
            slotsFilled = snapshot.slotsFilled,
            slots = slots,
            unslotted = unslotted,
            chosenPerks = chosenPerks,
            pendingPerkChoices = pendingPerkChoices(snapshot, chosenPerks),
        )
    }

    private fun pendingPerkChoices(
        snapshot: AgentSkillsSnapshot,
        chosen: List<ChosenPerkView>,
    ): List<PendingPerkChoiceView> {
        val chosenKeys = chosen.map { it.skillId to it.milestone }.toSet()
        return snapshot.perSkill.values
            .filter { it.slotIndex != null }
            .flatMap { state -> pendingForSkill(state.skill, state.level, chosenKeys) }
            .sortedWith(compareBy({ it.skillId }, { it.milestone }))
    }

    private fun pendingForSkill(
        skill: SkillId,
        level: Int,
        chosenKeys: Set<Pair<String, Int>>,
    ): List<PendingPerkChoiceView> = perkCatalog.choicesFor(skill)
        .filter { it.milestoneLevel <= level && (skill.value to it.milestoneLevel) !in chosenKeys }
        .map { choice ->
            PendingPerkChoiceView(
                skillId = skill.value,
                milestone = choice.milestoneLevel,
                options = choice.options.map { it.id.value },
            )
        }
}
