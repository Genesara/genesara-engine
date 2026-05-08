package dev.gvart.genesara.player

/**
 * Resolves an [AbilityId] to the chosen [Perk] backing it for a specific agent —
 * but only when the parent skill is currently slotted, mirroring
 * [TriggeredPassiveLookup]'s slot gate. Returns null when no chosen perk grants
 * [ability], the perk's parent skill is not in a slot, or the perk effect is not
 * an [PerkEffect.ActiveAbility].
 */
interface ActivePerkLookup {
    fun byAbility(agent: AgentId, ability: AbilityId): ActivePerk?
}

data class ActivePerk(
    val perk: Perk,
    val effect: PerkEffect.ActiveAbility,
)
