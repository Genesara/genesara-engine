package dev.gvart.genesara.player

/** Closed union of perk effect kinds — see `docs/skill-feature-sequence.md` decisions §5/§7/§8/§10/§11. */
sealed interface PerkEffect {

    data class ActiveAbility(
        val abilityId: String,
        val costResource: AbilityCostResource,
        val costAmount: Int,
        val target: AbilityTarget,
        val cooldownTicks: Int,
    ) : PerkEffect

    data class PassiveAura(
        val auraKey: String,
        val magnitude: Double,
    ) : PerkEffect

    data class TriggeredPassive(
        val trigger: TriggeredPassiveTrigger,
        val effectKind: TriggeredPassiveEffectKind,
        val params: Map<String, String>,
        val internalCooldownTicks: Int,
    ) : PerkEffect

    /**
     * Multiplier applied per-skill to the [LevelEffect] scaling rate. Scopes to the
     * skill that owns the perk: a Modifier perk on SWORD only multiplies SWORD's own
     * `level × perLevelPct` contribution, not contributions from other slotted skills
     * that scale the same [ScalingEffect].
     */
    data class Modifier(
        val target: ScalingEffect,
        val multiplier: Double,
    ) : PerkEffect
}

enum class AbilityCostResource { HP, STAMINA, MANA }

enum class AbilityTarget { SELF, SINGLE_AGENT, AREA_SELF_NODE }

enum class TriggeredPassiveTrigger {
    ON_HIT_TAKEN,
    ON_HIT_DEALT,
    ON_CRIT,
    ON_KILL,
    ON_LOW_HP,
    ON_DODGE,
    ON_HARVEST_COMPLETE,
    ON_CRAFT_COMPLETE,
    ON_BUILD_COMPLETE,
}

enum class TriggeredPassiveEffectKind {
    GRANT_SELF_BUFF,
    HEAL_SELF,
    DEAL_BONUS_DAMAGE,
    APPLY_STATUS_TO_TARGET,
    TELEPORT_NODES,
    REFUND_RESOURCE,
    GRANT_BONUS_ITEM,
}
