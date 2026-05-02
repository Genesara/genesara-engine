package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint

internal data class BuildingProperties(
    val requiredSkill: String,
    val requiredSkillLevel: Int = 1,
    val totalSteps: Int,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val totalMaterials: Map<String, Int> = emptyMap(),
    val chestCapacityGrams: Int? = null,
)
