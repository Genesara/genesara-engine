package dev.gvart.genesara.world.economy.internal.crafting

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "recipes")
data class RecipeDefinitionProperties(
    val catalog: Map<String, RecipeProperties> = emptyMap(),
)
