package dev.gvart.genesara.player.internal.perks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.TriggeredPassiveLookup
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.player.TriggeredPerk
import org.springframework.stereotype.Component

// TODO(perks-hot-path): AttackReducer fires up to 5 triggers per attack, each
// invoking matching() and re-snapshotting agent_perks + agent_skills. Add a
// matchingMany(triggers: Set<…>) so one snapshot pair covers a whole attack —
// or memoize per-tick — before this becomes the dominant query cost on the
// combat path.
@Component
internal class TriggeredPassiveLookupImpl(
    private val perks: PerkLookup,
    private val perksRegistry: AgentPerksRegistry,
    private val skillsRegistry: AgentSkillsRegistry,
) : TriggeredPassiveLookup {

    override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<TriggeredPerk> {
        val chosen = perksRegistry.snapshot(agent).perks
        if (chosen.isEmpty()) return emptyList()

        val slottedSkills = skillsRegistry.snapshot(agent).perSkill.values
            .filter { it.slotIndex != null }
            .mapTo(mutableSetOf()) { it.skill }
        if (slottedSkills.isEmpty()) return emptyList()

        return chosen.mapNotNull { chosenPerk ->
            if (chosenPerk.skill !in slottedSkills) return@mapNotNull null
            val perk = perks.byId(chosenPerk.perkId) ?: return@mapNotNull null
            val effect = perk.effect as? PerkEffect.TriggeredPassive ?: return@mapNotNull null
            if (effect.trigger != trigger) return@mapNotNull null
            TriggeredPerk(perk, effect)
        }
    }
}
