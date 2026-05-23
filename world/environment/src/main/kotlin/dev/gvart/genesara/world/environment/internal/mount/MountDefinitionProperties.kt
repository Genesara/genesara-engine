package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.MountSlot
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mounts")
internal data class MountDefinitionProperties(
    val catalog: Map<String, MountProperties> = emptyMap(),
)

internal data class MountProperties(
    val displayName: String = "",
    /** Source NPC type name; null for transports with no wild source. */
    val tamedFrom: String? = null,
    val tameability: Int = 0,
    val hpMax: Int = 1,
    val hungerMax: Int = 100,
    val fatigueMax: Int = 100,
    val speedFactor: Double = 1.0,
    val carryCapacityGrams: Int = 0,
    val defense: Int = 0,
    val gearSlots: Set<MountSlot> = emptySet(),
    val maintenanceType: MaintenanceType = MaintenanceType.ANIMAL,
)
