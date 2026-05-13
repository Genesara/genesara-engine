package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType

internal data class BuildingDef(
    val type: BuildingType,
    val skillBars: List<BarDef>,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val chestCapacityGrams: Int? = null,
) {
    val totalSteps: Int = skillBars.sumOf { it.steps }

    val isSingleBar: Boolean = skillBars.size == 1

    fun bar(skill: SkillId): BarDef? = skillBars.firstOrNull { it.skill == skill }

    fun defaultBar(): BarDef =
        skillBars.singleOrNull() ?: error("defaultBar() requires single-bar building; $type has ${skillBars.size} bars")
}
