package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint

internal data class BuildingProperties(
    val requiredSkill: String,
    /** Defaults to 0 — basic action, no skill-level gate. See [BuildingDef.requiredSkillLevel]. */
    val requiredSkillLevel: Int = 0,
    val totalSteps: Int,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val totalMaterials: Map<String, Int> = emptyMap(),
    val chestCapacityGrams: Int? = null,
)
