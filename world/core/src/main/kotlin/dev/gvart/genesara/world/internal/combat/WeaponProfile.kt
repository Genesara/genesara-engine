package dev.gvart.genesara.world.internal.combat

import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import kotlin.math.roundToInt

/**
 * Resolved weapon stats for one attack — used by every attack reducer
 * (AttackTarget, AttackNpc, AttackMount) so the unarmed-fallback rules and
 * the rarity multiplier on weaponPower live in exactly one place.
 */
data class WeaponProfile(
    val damageType: DamageType,
    val weaponPower: Int,
    val combatSkill: SkillId,
    val range: Int,
)

fun weaponProfileFor(
    weapon: Item?,
    instance: ItemInstance.Equipment?,
    balance: BalanceLookup,
): WeaponProfile {
    val damageType = weapon?.damageType ?: balance.unarmedDamageType()
    val basePower = weapon?.weaponPower ?: balance.unarmedWeaponPower()
    val weaponPower = if (weapon != null && instance != null) {
        (basePower * balance.rarityMultiplier(instance.rarity)).roundToInt().coerceAtLeast(0)
    } else {
        basePower
    }
    val combatSkill = weapon?.combatSkill ?: balance.unarmedCombatSkill()
    val range = weapon?.range ?: balance.unarmedRange()
    return WeaponProfile(damageType, weaponPower, combatSkill, range)
}

/** Damage-type to ScalingEffect mapping, shared across reducers that scale damage. */
fun scalingEffectFor(type: DamageType): ScalingEffect? = when (type) {
    DamageType.SLASH -> ScalingEffect.SLASH_DAMAGE_BONUS
    DamageType.PIERCE -> ScalingEffect.PIERCE_DAMAGE_BONUS
    DamageType.BLUNT -> ScalingEffect.BLUNT_DAMAGE_BONUS
    DamageType.ENERGY -> ScalingEffect.ENERGY_DAMAGE_BONUS
    DamageType.MAGICAL -> null
}
