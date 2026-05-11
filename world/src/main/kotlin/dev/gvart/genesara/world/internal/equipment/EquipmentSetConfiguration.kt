package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.world.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:world-definition/equipment-sets.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(EquipmentSetDefinitionProperties::class)
internal class EquipmentSetConfiguration
