package dev.gvart.genesara.world.internal.buildings

data class BarProperties(
    val level: Int = 0,
    val steps: Int,
    val materialsPerStep: Map<String, Int> = emptyMap(),
)
