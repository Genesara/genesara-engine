package dev.gvart.genesara.player

interface TriggeredPassiveLookup {

    fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<TriggeredPerk>
}

data class TriggeredPerk(
    val perk: Perk,
    val effect: PerkEffect.TriggeredPassive,
)
