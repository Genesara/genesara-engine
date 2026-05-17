package dev.gvart.genesara.world.internal.npc

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "loot-tables")
internal data class LootDefinitionProperties(
    val catalog: Map<String, LootMobProperties> = emptyMap(),
)

internal data class LootMobProperties(
    val drops: List<LootDropProperties> = emptyList(),
)

internal data class LootDropProperties(
    val item: String = "",
    val dropChance: Double = 0.0,
    val quantityMin: Int = 1,
    val quantityMax: Int = 1,
)
