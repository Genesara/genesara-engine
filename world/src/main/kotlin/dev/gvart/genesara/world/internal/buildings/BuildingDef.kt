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
    /**
     * Skill the build action trains and is recommended for. Always used as an XP +
     * recommendation hook; gated as a hard prerequisite when [requiredSkillLevel] > 0.
     */
    val requiredSkill: SkillId,
    /**
     * Minimum [requiredSkill] level the agent must hold to start or advance this build.
     * `0` (default) means "basic action, no level gate" — every Tier-1 building in v1.
     * Higher tiers and certain Tier-1 specialty buildings may set this >0 in the future.
     */
    val requiredSkillLevel: Int,
    val totalSteps: Int,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val chestCapacityGrams: Int? = null,
)
