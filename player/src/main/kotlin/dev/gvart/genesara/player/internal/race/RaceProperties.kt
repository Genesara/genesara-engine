package dev.gvart.genesara.player.internal.race

internal data class RaceProperties(
    val displayName: String,
    val weight: Int = 1,
    val description: String = "",
    val attributeMods: AttributeModsProperties = AttributeModsProperties(),
)

internal data class AttributeModsProperties(
    val strength: Int = 0,
    val dexterity: Int = 0,
    val constitution: Int = 0,
    val perception: Int = 0,
    val intelligence: Int = 0,
    val luck: Int = 0,
)
