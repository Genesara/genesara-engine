package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "player")
internal data class ClassDefinitionProperties(
    val default: ClassProperties = ClassProperties(displayName = "Unassigned", sightRange = 1),
    val classes: Map<AgentClass, ClassProperties> = emptyMap(),
)
