package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = [
        "classpath:world-definition/npcs.yaml",
        "classpath:world-definition/loot-tables.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(NpcDefinitionProperties::class, LootDefinitionProperties::class)
internal class NpcCatalogConfiguration {

    @Bean
    internal fun npcCatalog(properties: NpcDefinitionProperties): NpcCatalog {
        val byType = properties.catalog.mapValues { (id, def) -> def.toDomain(NpcType(id)) }
        val byBiome = Biome.entries.associateWith { biome ->
            byType.values.filter { biome in it.spawnBiomes }
        }
        return InMemoryNpcCatalog(byType, byBiome)
    }

    @Bean
    internal fun lootTableCatalog(properties: LootDefinitionProperties): LootTableCatalog {
        val byMob = properties.catalog.mapKeys { (mob, _) -> NpcType(mob) }
            .mapValues { (_, def) ->
                def.drops.map { drop ->
                    LootEntry(
                        item = ItemId(drop.item),
                        dropChance = drop.dropChance,
                        quantityMin = drop.quantityMin,
                        quantityMax = drop.quantityMax,
                    )
                }
            }
        return InMemoryLootTableCatalog(byMob)
    }
}

internal class InMemoryNpcCatalog(
    private val byType: Map<String, NpcDef>,
    private val byBiome: Map<Biome, List<NpcDef>>,
) : NpcCatalog {
    override fun byType(type: NpcType): NpcDef? = byType[type.value]
    override fun all(): Collection<NpcDef> = byType.values
    override fun byBiome(biome: Biome): List<NpcDef> = byBiome[biome] ?: emptyList()
}

internal class InMemoryLootTableCatalog(
    private val byMob: Map<NpcType, List<LootEntry>>,
) : LootTableCatalog {
    override fun byMob(mob: NpcType): List<LootEntry> = byMob[mob] ?: emptyList()
    override fun allMobs(): Set<NpcType> = byMob.keys
}

private fun NpcProperties.toDomain(type: NpcType): NpcDef = NpcDef(
    type = type,
    displayName = displayName,
    hpMax = hpMax,
    damage = damage,
    damageType = damageType,
    range = range,
    attackIntervalTicks = attackIntervalTicks,
    defense = defense,
    dodgeChancePercent = dodgeChancePercent,
    aggressionProfile = aggressionProfile,
    territoryRadius = territoryRadius,
    spawnBiomes = spawnBiomes.toSet(),
    spawnWeight = spawnWeight.coerceAtLeast(1),
    fleeDistance = fleeDistance.coerceAtLeast(1),
)
