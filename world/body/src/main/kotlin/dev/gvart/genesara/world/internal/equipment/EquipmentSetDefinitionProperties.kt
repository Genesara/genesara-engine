package dev.gvart.genesara.world.internal.equipment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sets")
data class EquipmentSetDefinitionProperties(
    val catalog: Map<String, EquipmentSetProperties> = emptyMap(),
)
