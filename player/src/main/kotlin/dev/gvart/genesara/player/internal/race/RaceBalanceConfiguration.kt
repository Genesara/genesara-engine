package dev.gvart.genesara.player.internal.race

import dev.gvart.genesara.player.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:player-definition/races.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(RaceDefinitionProperties::class)
internal class RaceBalanceConfiguration
