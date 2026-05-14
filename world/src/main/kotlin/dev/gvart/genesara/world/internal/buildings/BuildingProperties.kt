package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint

internal data class BuildingProperties(
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val skillBars: Map<String, BarProperties>,
    val chestCapacityGrams: Int? = null,
)
