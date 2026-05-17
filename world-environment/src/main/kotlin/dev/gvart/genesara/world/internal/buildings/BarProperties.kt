package dev.gvart.genesara.world.internal.buildings

internal data class BarProperties(
    val level: Int = 0,
    val steps: Int,
    val materialsPerStep: Map<String, Int> = emptyMap(),
)
