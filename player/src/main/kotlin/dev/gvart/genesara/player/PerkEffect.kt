package dev.gvart.genesara.player

/** Closed union of perk effect kinds — see `docs/skill-feature-sequence.md` decisions §5/§7/§8/§10/§11. */
sealed interface PerkEffect {

    /**
     * Agent calls `use_ability(abilityId, target?)`, the reducer pays [costAmount]
     * of [costResource] at cast, the [effectKind] resolves at the next tick, and
     * [PerkCooldownStore] arms for [cooldownTicks]. [effectParams] mirrors
     * [TriggeredPassive.params] — string-typed bag the resolver parses per
     * [effectKind] (e.g. SCALE_NEXT_ATTACK reads `multiplierPct`).
     */
    data class ActiveAbility(
        val abilityId: AbilityId,
        val costResource: AbilityCostResource,
        val costAmount: Int,
        val target: AbilityTarget,
        val cooldownTicks: Int,
        val effectKind: AbilityEffectKind,
        val effectParams: Map<String, String>,
    ) : PerkEffect

    /**
     * Always-on flat bonus while the granting skill is slotted AND the perk has been chosen.
     * Sums into [PassiveAuraAggregator]; reducers add the aggregate as a flat post-scaling
     * term, distinct from [Modifier] which multiplies [LevelScalingAggregator]'s rate.
     */
    data class PassiveAura(
        val target: ScalingEffect,
        val magnitude: Int,
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

enum class AbilityEffectKind {
    /** Buff stored on the agent; consumed by the next [WorldCommand.AttackTarget] resolver as a damage multiplier. */
    SCALE_NEXT_ATTACK,
    HEAL_SELF,
    DEAL_BONUS_DAMAGE,
    APPLY_STATUS_TO_TARGET,
    GRANT_SELF_BUFF,
    TELEPORT_NODES,
}

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
    /** Fires for both parties when a trade offer is accepted (BARTERING-driven perks). */
    ON_TRADE_COMPLETED,
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
