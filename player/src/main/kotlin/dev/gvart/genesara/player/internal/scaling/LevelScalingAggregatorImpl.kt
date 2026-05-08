package dev.gvart.genesara.player.internal.scaling

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import org.springframework.stereotype.Component

@Component
internal class LevelScalingAggregatorImpl(
    private val skills: SkillLookup,
    private val perks: PerkLookup,
    private val skillsRegistry: AgentSkillsRegistry,
    private val perksRegistry: AgentPerksRegistry,
) : LevelScalingAggregator {

    override fun bonusFor(agent: AgentId, effect: ScalingEffect): Double {
        val skillsSnapshot = skillsRegistry.snapshot(agent)
        val slotted = skillsSnapshot.perSkill.values.filter { it.slotIndex != null }
        if (slotted.isEmpty()) return 0.0

        val modifierBySkill = chosenModifiersFor(agent, effect)
        return slotted.sumOf { state ->
            val skill = skills.byId(state.skill) ?: return@sumOf 0.0
            val levelEffect = skill.levelEffect ?: return@sumOf 0.0
            if (levelEffect.type != effect) return@sumOf 0.0
            val base = state.level * levelEffect.perLevelPct
            val multiplier = modifierBySkill[state.skill] ?: 1.0
            base * multiplier
        }
    }

    private fun chosenModifiersFor(agent: AgentId, effect: ScalingEffect): Map<SkillId, Double> {
        val chosen = perksRegistry.snapshot(agent).perks
        if (chosen.isEmpty()) return emptyMap()
        val byOwningSkill = mutableMapOf<SkillId, Double>()
        for (chosenPerk in chosen) {
            val perk = perks.byId(chosenPerk.perkId) ?: continue
            val modifier = perk.effect as? PerkEffect.Modifier ?: continue
            if (modifier.target != effect) continue
            byOwningSkill.merge(perk.skill, modifier.multiplier, Double::times)
        }
        return byOwningSkill
    }
}
