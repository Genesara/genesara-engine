package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Terrain
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "world")
internal data class WorldDefinitionProperties(
    val biomes: Map<Biome, BiomeProperties> = emptyMap(),
    val climates: Map<Climate, ClimateProperties> = emptyMap(),
    val terrains: Map<Terrain, TerrainProperties> = emptyMap(),
)