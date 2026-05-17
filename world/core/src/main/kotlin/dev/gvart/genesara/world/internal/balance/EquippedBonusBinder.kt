package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquippedBonus

/**
 * Dispatches a YAML `{ target, magnitude }` bonus entry into the typed
 * [EquippedBonus] variant by checking [target] against three enum lists.
 * Throws on unknown targets — the calling context names the source
 * ([sourceId]: item id, set id, etc.) for the error message.
 */
fun EquippedBonusProperties.bindToDomain(sourceId: String): EquippedBonus {
    val raw = target
    DamageType.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.ArmorDef(it, magnitude) }
    Attribute.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.AttributeBonus(it, magnitude) }
    ScalingEffect.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.PassiveBuff(it, magnitude) }
    error("$sourceId: unknown bonus target '$raw' (expected one of DamageType, Attribute, or ScalingEffect)")
}
