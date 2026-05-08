package dev.gvart.genesara.player.internal.scaling

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.ScalingEffect
import org.springframework.stereotype.Component

@Component
internal class PassiveAuraAggregatorImpl(
    private val perks: PerkLookup,
    private val skillsRegistry: AgentSkillsRegistry,
    private val perksRegistry: AgentPerksRegistry,
) : PassiveAuraAggregator {

    override fun bonusFor(agent: AgentId, effect: ScalingEffect): Int {
        val chosen = perksRegistry.snapshot(agent).perks
        if (chosen.isEmpty()) return 0

        val slottedSkillIds = skillsRegistry.snapshot(agent).perSkill.values
            .filter { it.slotIndex != null }
            .map { it.skill }
            .toSet()
        if (slottedSkillIds.isEmpty()) return 0

        var sum = 0
        for (chosenPerk in chosen) {
            val perk = perks.byId(chosenPerk.perkId) ?: continue
            if (perk.skill !in slottedSkillIds) continue
            val aura = perk.effect as? PerkEffect.PassiveAura ?: continue
            if (aura.target != effect) continue
            sum += aura.magnitude
        }
        return sum
    }
}
