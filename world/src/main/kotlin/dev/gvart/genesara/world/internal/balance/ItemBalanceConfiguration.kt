package dev.gvart.genesara.world.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:world-definition/items.yaml",
        "classpath:world-definition/equipment.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(ItemDefinitionProperties::class)
internal class ItemBalanceConfiguration
