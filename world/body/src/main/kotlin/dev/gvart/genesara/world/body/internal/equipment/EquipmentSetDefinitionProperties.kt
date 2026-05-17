package dev.gvart.genesara.world.body.internal.equipment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sets")
internal data class EquipmentSetDefinitionProperties(
    val catalog: Map<String, EquipmentSetProperties> = emptyMap(),
)
