package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.events.CombatEvent

/**
 * Per-agent lookup for set-bonus triggered passives — the sister surface to
 * [dev.gvart.genesara.player.TriggeredPassiveLookup] but sourced from
 * equipped set pieces instead of chosen perks.
 *
 * The dispatcher combines both lookups when firing
 * [CombatEvent.PerkTriggered][dev.gvart.genesara.world.events.CombatEvent.PerkTriggered]
 * events; set bonuses get a synthetic perk id `"set:<setId>@<tier>:<index>"`
 * so the existing [dev.gvart.genesara.player.PerkCooldownStore] keys them
 * without a parallel store.
 */
interface EquipmentSetTriggerLookup {

    fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<ActiveSetTrigger>

    companion object {
        /** Empty-result lookup for tests that don't exercise set-bonus triggers. */
        val NoSetTriggers: EquipmentSetTriggerLookup = object : EquipmentSetTriggerLookup {
            override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<ActiveSetTrigger> = emptyList()
        }
    }
}

data class ActiveSetTrigger(
    val setId: EquipmentSetId,
    val tier: Int,
    val indexInTier: Int,
    val effect: PerkEffect.TriggeredPassive,
) {
    /** Synthetic perk id used by the dispatcher for cooldown keying and event emission. */
    val syntheticPerkId: String = "set:${setId.value}@$tier:$indexInTier"
}
