package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId

/**
 * Resolved per-type build spec. Per-step math: `floor(total / steps)` for
 * steps 1..N-1, remainder on step N so totals match exactly per material.
 */
internal data class BuildingDef(
    val type: BuildingType,
    val totalMaterials: Map<ItemId, Int>,
    val stepMaterials: List<Map<ItemId, Int>>,
    val requiredSkill: SkillId,
    val requiredSkillLevel: Int,
    val totalSteps: Int,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val chestCapacityGrams: Int? = null,
)
