package dev.gvart.genesara.world.economy.internal.cultivation

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crops")
internal data class CropDefinitionProperties(
    val catalog: Map<String, CropProperties> = emptyMap(),
)
