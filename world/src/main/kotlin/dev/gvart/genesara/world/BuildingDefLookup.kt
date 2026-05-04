package dev.gvart.genesara.world

import dev.gvart.genesara.player.SkillId

/**
 * Public read-only surface over the building catalog. Lets `:api` projections (inspect)
 * surface per-type spec data without touching the internal `BuildingsCatalog` directly.
 * The internal catalog still owns the YAML binding + per-step material distribution.
 */
interface BuildingDefLookup {
    fun byType(type: BuildingType): BuildingDefView?
    fun all(): List<BuildingDefView>
}

/**
 * Public-API projection of a [BuildingType]'s catalog spec. Includes everything an
 * inspecting agent might want to know about how to build (or finish building) the type:
 * total + per-step materials, the required skill + level gate (0 = no gate), per-step
 * stamina and total step count, base HP, and the chest weight cap when applicable.
 */
data class BuildingDefView(
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
