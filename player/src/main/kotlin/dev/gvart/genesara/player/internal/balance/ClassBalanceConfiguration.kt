package dev.gvart.genesara.player.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:player-definition/classes/defaults.yaml",
        "classpath:player-definition/classes/artisan.yaml",
        "classpath:player-definition/classes/engineer.yaml",
        "classpath:player-definition/classes/hunter.yaml",
        "classpath:player-definition/classes/medic.yaml",
        "classpath:player-definition/classes/merchant.yaml",
        "classpath:player-definition/classes/researcher.yaml",
        "classpath:player-definition/classes/scout.yaml",
        "classpath:player-definition/classes/soldier.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(ClassDefinitionProperties::class)
internal class ClassBalanceConfiguration
