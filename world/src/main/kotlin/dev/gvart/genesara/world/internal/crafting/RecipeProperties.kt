package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.world.BuildingCategoryHint

internal data class RecipeProperties(
    val output: RecipeOutputProperties,
    val inputs: Map<String, Int> = emptyMap(),
    val requiredStation: BuildingCategoryHint,
    val requiredSkill: String,
    val requiredSkillLevel: Int = 0,
    val staminaCost: Int,
)

internal data class RecipeOutputProperties(
    val item: String,
    val quantity: Int = 1,
)
