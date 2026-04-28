package dev.gvart.genesara.world.internal.balance

internal data class TerrainProperties(
    val displayName: String,
    val traversable: Boolean = true,
    val movementCostMultiplier: Double = .5,
)