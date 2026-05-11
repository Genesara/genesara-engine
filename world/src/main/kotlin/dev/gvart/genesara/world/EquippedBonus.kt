package dev.gvart.genesara.world

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect

/**
 * One row of an equipment item's `bonuses:` list. Dispatched at YAML bind time
 * by the `target` field's enum membership — see ADR-0001.
 *
 * Stays a sealed type so consumers (aggregators, projections) match
 * exhaustively. Hot-path readers (combat reducer, derived-pool calculators)
 * filter by variant at the aggregator layer rather than instance-of'ing the
 * whole equipped-set on every tick.
 */
sealed interface EquippedBonus {
    val magnitude: Int

    data class ArmorDef(val damageType: DamageType, override val magnitude: Int) : EquippedBonus
    data class AttributeBonus(val attribute: Attribute, override val magnitude: Int) : EquippedBonus
    // TODO(magic-class): ScalingEffect has no MAGICAL_DAMAGE_BONUS variant yet;
    // PassiveBuff coverage is asymmetric with DamageType for the MAGICAL kind.
    data class PassiveBuff(val effect: ScalingEffect, override val magnitude: Int) : EquippedBonus
}
