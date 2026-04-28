package dev.gvart.genesara.world.internal.balance

internal data class ClimateProperties(
    val displayName: String,
    val staminaDrainPerTick: Double = 0.0,
    val farmingMultiplier: Double = 0.0,
    val manaRegenMultiplier: Double = 0.0,
)