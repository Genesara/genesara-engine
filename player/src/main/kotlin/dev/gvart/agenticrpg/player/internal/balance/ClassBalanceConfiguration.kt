package dev.gvart.agenticrpg.player.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:player-definition/classes.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(ClassDefinitionProperties::class)
internal class ClassBalanceConfiguration
