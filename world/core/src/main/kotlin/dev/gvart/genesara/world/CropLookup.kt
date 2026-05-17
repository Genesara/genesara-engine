package dev.gvart.genesara.world

import dev.gvart.genesara.player.SkillId

/**
 * Catalog entry for a cultivable crop. Yield is
 * `baseYield + floor(skillLevel * gainPerLevel) + uniform(0, maxLuckBonus)`.
 */
data class Crop(
    val id: CropId,
    val seedItem: ItemId,
    val ticksToRipe: Long,
    val outputItem: ItemId,
    val baseYield: Int,
    val neglectWindowTicks: Long,
    val requiredTerrain: Set<Terrain>,
    val requiredFarmingLevel: Int,
    val gainPerLevel: Double,
    val maxLuckBonus: Int,
    val staminaCostPlant: Int,
    val staminaCostTend: Int,
    val staminaCostHarvest: Int,
    val farmingSkill: SkillId,
)

interface CropLookup {
    fun byId(id: CropId): Crop?
    fun all(): List<Crop>
}
