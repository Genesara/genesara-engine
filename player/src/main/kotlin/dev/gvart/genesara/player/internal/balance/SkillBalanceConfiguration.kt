package dev.gvart.genesara.player.internal.balance

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:player-definition/skills/animal.yaml",
        "classpath:player-definition/skills/athletics.yaml",
        "classpath:player-definition/skills/class-locked.yaml",
        "classpath:player-definition/skills/combat.yaml",
        "classpath:player-definition/skills/crafting.yaml",
        "classpath:player-definition/skills/gathering.yaml",
        "classpath:player-definition/skills/knowledge.yaml",
        "classpath:player-definition/skills/social.yaml",
        "classpath:player-definition/skills/stealth.yaml",
        "classpath:player-definition/skills/survival.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(SkillDefinitionProperties::class)
internal class SkillBalanceConfiguration
