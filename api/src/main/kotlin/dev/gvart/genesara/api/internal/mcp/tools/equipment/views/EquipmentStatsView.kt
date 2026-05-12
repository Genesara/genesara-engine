package dev.gvart.genesara.api.internal.mcp.tools.equipment.views

import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.Rarity
import kotlin.math.roundToInt

data class EquipmentStatsView(
    val slots: List<String>,
    val twoHanded: Boolean,
    val maxDurability: Int?,
    val damageType: String?,
    val weaponPower: Int?,
    val range: Int?,
    val combatSkill: String?,
    val requiredAttributes: Map<String, Int>,
    val requiredSkills: Map<String, Int>,
    val bonuses: List<EquipmentBonusView>,
    /** Rarity-scaled `weaponPower` per tier (COMMON..LEGENDARY). Null when [weaponPower] is null. */
    val weaponPowerByRarity: Map<String, Int>? = null,
    /** Rarity-scaled `maxDurability` per tier (COMMON..LEGENDARY). Null when [maxDurability] is null. */
    val maxDurabilityByRarity: Map<String, Int>? = null,
)

data class EquipmentBonusView(
    val target: String,
    val magnitude: Int,
)

fun equipmentStatsViewOf(item: Item): EquipmentStatsView? {
    if (item.category != ItemCategory.EQUIPMENT) return null
    return EquipmentStatsView(
        slots = item.validSlots.map { it.name }.sorted(),
        twoHanded = item.twoHanded,
        maxDurability = item.maxDurability,
        damageType = item.damageType?.name,
        weaponPower = item.weaponPower,
        range = item.range,
        combatSkill = item.combatSkill?.value,
        requiredAttributes = item.requiredAttributes.mapKeys { it.key.name },
        requiredSkills = item.requiredSkills.mapKeys { it.key.value },
        bonuses = item.bonuses.map(::bonusView),
        weaponPowerByRarity = item.weaponPower?.let(::scaleByRarity),
        maxDurabilityByRarity = item.maxDurability?.let(::scaleByRarity),
    )
}

private fun bonusView(bonus: EquippedBonus): EquipmentBonusView = when (bonus) {
    is EquippedBonus.ArmorDef -> EquipmentBonusView(bonus.damageType.name, bonus.magnitude)
    is EquippedBonus.AttributeBonus -> EquipmentBonusView(bonus.attribute.name, bonus.magnitude)
    is EquippedBonus.PassiveBuff -> EquipmentBonusView(bonus.effect.name, bonus.magnitude)
}

private fun scaleByRarity(base: Int): Map<String, Int> =
    Rarity.entries.associate { it.name to (base * rarityMultiplier(it)).roundToInt().coerceAtLeast(0) }

// WHY: mirrors the curve in `BalanceLookup.rarityMultiplier` (ADR-0002). The world module
// keeps that lookup `internal`, so the api side inlines the same constants for the preview.
// Keep both sites in sync if the curve is retuned.
private fun rarityMultiplier(rarity: Rarity): Double = when (rarity) {
    Rarity.COMMON -> 1.0
    Rarity.UNCOMMON -> 1.25
    Rarity.RARE -> 1.5
    Rarity.EPIC -> 1.75
    Rarity.LEGENDARY -> 2.0
}
