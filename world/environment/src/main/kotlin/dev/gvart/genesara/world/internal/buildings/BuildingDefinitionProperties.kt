package dev.gvart.genesara.world.internal.buildings

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "buildings")
data class BuildingDefinitionProperties(
    val catalog: Map<String, BuildingProperties> = emptyMap(),
)
