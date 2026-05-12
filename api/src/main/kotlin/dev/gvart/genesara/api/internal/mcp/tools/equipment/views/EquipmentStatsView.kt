package dev.gvart.genesara.api.internal.mcp.tools.equipment.views

import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory

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
    )
}

private fun bonusView(bonus: EquippedBonus): EquipmentBonusView = when (bonus) {
    is EquippedBonus.ArmorDef -> EquipmentBonusView(bonus.damageType.name, bonus.magnitude)
    is EquippedBonus.AttributeBonus -> EquipmentBonusView(bonus.attribute.name, bonus.magnitude)
    is EquippedBonus.PassiveBuff -> EquipmentBonusView(bonus.effect.name, bonus.magnitude)
}
