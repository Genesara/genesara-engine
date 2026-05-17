package dev.gvart.genesara.world.internal.cultivation

import dev.gvart.genesara.world.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:world-definition/crops.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(CropDefinitionProperties::class)
internal class CropBalanceConfiguration
