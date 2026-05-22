package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.internal.balance.YamlPropertySourceFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

@Configuration
@PropertySource(
    value = ["classpath:world-definition/mounts.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@EnableConfigurationProperties(MountDefinitionProperties::class)
internal class MountCatalogConfiguration {

    @Bean
    internal fun mountCatalog(properties: MountDefinitionProperties): MountCatalog {
        val byType = properties.catalog.mapValues { (id, def) -> def.toDomain(MountType(id)) }
        val byTamedFrom = byType.values
            .filter { it.tamedFrom != null }
            .groupBy { it.tamedFrom!! }
            .mapValues { (npcType, defs) ->
                require(defs.size == 1) {
                    "Multiple mount types tame from the same NPC '${npcType.value}': " +
                        "${defs.map { it.type.value }}. Each NpcType maps to at most one MountType."
                }
                defs.single()
            }
        return InMemoryMountCatalog(byType, byTamedFrom)
    }
}

internal class InMemoryMountCatalog(
    private val byType: Map<String, MountDef>,
    private val byTamedFrom: Map<NpcType, MountDef>,
) : MountCatalog {
    override fun byType(type: MountType): MountDef? = byType[type.value]
    override fun byTamedFromNpc(npcType: NpcType): MountDef? = byTamedFrom[npcType]
    override fun all(): Collection<MountDef> = byType.values
}

private fun MountProperties.toDomain(type: MountType): MountDef = MountDef(
    type = type,
    displayName = displayName,
    tamedFrom = tamedFrom?.let(::NpcType),
    tameability = tameability,
    hpMax = hpMax,
    hungerMax = hungerMax,
    fatigueMax = fatigueMax,
    speedFactor = speedFactor,
    carryCapacityGrams = carryCapacityGrams,
    defense = defense,
    gearSlots = gearSlots,
    maintenanceType = maintenanceType,
)
