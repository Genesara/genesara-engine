package dev.gvart.genesara.player.internal.perks

import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.ActivePerk
import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkLookup
import org.springframework.stereotype.Component

@Component
internal class ActivePerkLookupImpl(
    private val perks: PerkLookup,
    private val perksRegistry: AgentPerksRegistry,
    private val skillsRegistry: AgentSkillsRegistry,
) : ActivePerkLookup {

    override fun byAbility(agent: AgentId, ability: AbilityId): ActivePerk? {
        val chosen = perksRegistry.snapshot(agent).perks
        if (chosen.isEmpty()) return null

        val slottedSkills = skillsRegistry.snapshot(agent).perSkill.values
            .filter { it.slotIndex != null }
            .mapTo(mutableSetOf()) { it.skill }
        if (slottedSkills.isEmpty()) return null

        for (chosenPerk in chosen) {
            if (chosenPerk.skill !in slottedSkills) continue
            val perk = perks.byId(chosenPerk.perkId) ?: continue
            val effect = perk.effect as? PerkEffect.ActiveAbility ?: continue
            if (effect.abilityId == ability) return ActivePerk(perk, effect)
        }
        return null
    }
}
