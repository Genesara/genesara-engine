package dev.gvart.genesara.world.economy.internal.cultivation

import dev.gvart.genesara.world.Terrain

internal data class CropProperties(
    val seedItem: String,
    val ticksToRipe: Long,
    val outputItem: String,
    val baseYield: Int,
    val neglectWindowTicks: Long,
    val requiredTerrain: List<Terrain>,
    val requiredFarmingLevel: Int = 0,
    val gainPerLevel: Double = 0.0,
    val maxLuckBonus: Int = 0,
    val staminaCostPlant: Int,
    val staminaCostTend: Int,
    val staminaCostHarvest: Int,
)
