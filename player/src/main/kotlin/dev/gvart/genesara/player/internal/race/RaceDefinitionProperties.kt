package dev.gvart.genesara.player.internal.race

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "races")
internal data class RaceDefinitionProperties(
    val defaultId: String = "human_commoner",
    val catalog: Map<String, RaceProperties> = emptyMap(),
)
