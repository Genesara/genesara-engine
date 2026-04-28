package dev.gvart.genesara.world.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:world-definition/biomes.yaml",
        "classpath:world-definition/climates.yaml",
        "classpath:world-definition/terrains.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(WorldDefinitionProperties::class)
internal class WorldBalanceConfiguration