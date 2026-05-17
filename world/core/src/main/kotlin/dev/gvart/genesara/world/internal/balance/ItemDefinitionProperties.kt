package dev.gvart.genesara.world.internal.balance

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "items")
data class ItemDefinitionProperties(
    val catalog: Map<String, ItemProperties> = emptyMap(),
)
