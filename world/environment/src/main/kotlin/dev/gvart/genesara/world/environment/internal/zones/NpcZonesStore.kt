package dev.gvart.genesara.world.environment.internal.zones

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldId
import java.util.UUID

internal interface NpcZonesStore {
    fun listByWorld(worldId: WorldId): List<NpcZone>
    fun findById(zoneId: UUID): NpcZone?
    fun findActiveByNode(nodeId: NodeId): NpcZone?
    fun findActiveByRegion(regionId: RegionId): NpcZone?
    fun insert(zone: NpcZone)
    fun update(zone: NpcZone): NpcZone?
    fun delete(zoneId: UUID): Boolean
}
