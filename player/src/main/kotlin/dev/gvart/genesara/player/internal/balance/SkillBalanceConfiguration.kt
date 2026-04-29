package dev.gvart.genesara.player.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:player-definition/skills.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(SkillDefinitionProperties::class)
internal class SkillBalanceConfiguration
