package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.DamageType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "npcs")
data class NpcDefinitionProperties(
    val catalog: Map<String, NpcProperties> = emptyMap(),
)

data class NpcProperties(
    val displayName: String = "",
    val hpMax: Int = 1,
    val damage: Int = 0,
    val damageType: DamageType = DamageType.BLUNT,
    val range: Int = 1,
    val attackIntervalTicks: Int = 4,
    val defense: Int = 0,
    val dodgeChancePercent: Int = 0,
    val aggressionProfile: AggressionProfile = AggressionProfile.PASSIVE,
    val territoryRadius: Int = 0,
    val spawnBiomes: List<Biome> = emptyList(),
    val spawnWeight: Int = 1,
    val fleeDistance: Int = 1,
)
