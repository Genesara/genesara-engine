package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger

internal data class SkillProperties(
    val displayName: String = "",
    val description: String = "",
    val category: SkillCategory = SkillCategory.SURVIVAL,
    val levelEffect: LevelEffectProperties? = null,
    /**
     * YAML keys are strings ("50", "100", "150"); converted to Int milestone levels in
     * [SkillLookupImpl] / [PerkLookupImpl]. The validator enforces the legal level set.
     */
    val milestones: Map<String, List<PerkProperties>> = emptyMap(),
)

internal data class LevelEffectProperties(
    val type: ScalingEffect? = null,
    val perLevelPct: Double? = null,
)

internal data class PerkProperties(
    val id: String = "",
    val displayName: String = "",
    val description: String = "",
    val effect: PerkEffectProperties = PerkEffectProperties(),
)

/**
 * Flat union — the YAML carries one [type] discriminator and the fields the validator
 * requires for that type. Translation to the sealed [dev.gvart.genesara.player.PerkEffect]
 * happens in [PerkLookupImpl]; the validator rejects mismatched / missing fields before
 * a translation is ever attempted.
 */
internal data class PerkEffectProperties(
    val type: PerkEffectType? = null,
    val abilityId: String? = null,
    val costResource: AbilityCostResource? = null,
    val costAmount: Int? = null,
    val abilityTarget: AbilityTarget? = null,
    val cooldownTicks: Int? = null,
    val auraTarget: ScalingEffect? = null,
    val auraMagnitude: Int? = null,
    val trigger: TriggeredPassiveTrigger? = null,
    val effectKind: TriggeredPassiveEffectKind? = null,
    val params: Map<String, String> = emptyMap(),
    val internalCooldownTicks: Int? = null,
    val modifierTarget: ScalingEffect? = null,
    val multiplier: Double? = null,
)

internal enum class PerkEffectType {
    ACTIVE_ABILITY,
    PASSIVE_AURA,
    TRIGGERED_PASSIVE,
    MODIFIER,
}
