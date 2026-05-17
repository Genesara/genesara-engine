package dev.gvart.genesara.world.internal.crafting

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "recipes")
internal data class RecipeDefinitionProperties(
    val catalog: Map<String, RecipeProperties> = emptyMap(),
)
