package dev.gvart.genesara.player.internal.balance

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "skills")
internal data class SkillDefinitionProperties(
    val catalog: Map<String, SkillProperties> = emptyMap(),
)
