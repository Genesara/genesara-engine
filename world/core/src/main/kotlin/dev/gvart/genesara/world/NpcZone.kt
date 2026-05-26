package dev.gvart.genesara.world

import java.util.UUID

/**
 * Operator overlay over biome-driven spawn rules; exactly one of [regionId] / [nodeId]
 * is non-null per [scope], and the catalog's `spawnBiomes` filter is still enforced.
 */
data class NpcZone(
    val zoneId: UUID,
    val worldId: WorldId,
    val scope: NpcZoneScope,
    val regionId: RegionId?,
    val nodeId: NodeId?,
    val weights: Map<NpcType, Int>,
    val maxConcurrent: Int,
    val respawnTicks: Int?,
    val active: Boolean,
    val createdBy: UUID,
    val createdAtTick: Long,
)

enum class NpcZoneScope { REGION, NODE }
