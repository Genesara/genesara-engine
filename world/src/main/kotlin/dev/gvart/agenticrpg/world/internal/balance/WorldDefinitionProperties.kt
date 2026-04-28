package dev.gvart.agenticrpg.world.internal.balance

import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.Terrain
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "world")
internal data class WorldDefinitionProperties(
    val biomes: Map<Biome, BiomeProperties> = emptyMap(),
    val climates: Map<Climate, ClimateProperties> = emptyMap(),
    val terrains: Map<Terrain, TerrainProperties> = emptyMap(),
)