package dev.gvart.genesara.world

import dev.gvart.genesara.player.SkillId

/**
 * Public read-only surface over the building catalog. Lets `:api` projections (inspect)
 * surface per-type spec data without touching the internal `BuildingsCatalog` directly.
 */
interface BuildingDefLookup {
    fun byType(type: BuildingType): BuildingDefView?
    fun all(): List<BuildingDefView>
}

/**
 * Public-API projection of a [BuildingType]'s catalog spec. Every building has one or
 * more skill-bars: a Tier-1 building has a single bar; a multi-skill Tier-2 building
 * lists each required-skill domain as a separate bar with its own progress, materials,
 * and skill-level gate.
 */
data class BuildingDefView(
    val type: BuildingType,
    val skillBars: List<BuildingBarView>,
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val chestCapacityGrams: Int? = null,
) {
    val totalSteps: Int get() = skillBars.sumOf { it.steps }
}

/** Public-API per-bar projection. */
data class BuildingBarView(
    val skill: SkillId,
    val level: Int,
    val steps: Int,
    val materialsPerStep: Map<ItemId, Int>,
) {
    val totalMaterials: Map<ItemId, Int> get() = materialsPerStep.mapValues { (_, qty) -> qty * steps }
}
