package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.world.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:world-definition/recipes-intermediates.yaml",
        "classpath:world-definition/recipes-weapons.yaml",
        "classpath:world-definition/recipes-armor.yaml",
        "classpath:world-definition/recipes-jewelry.yaml",
        "classpath:world-definition/recipes-consumables.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(RecipeDefinitionProperties::class)
internal class RecipeBalanceConfiguration
