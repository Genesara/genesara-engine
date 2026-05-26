package dev.gvart.genesara.world.environment.internal.zones

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneLookup
import dev.gvart.genesara.world.RegionId
import org.springframework.stereotype.Component

@Component
internal class NpcZoneLookupImpl(
    private val store: NpcZonesStore,
) : NpcZoneLookup {
    override fun resolveFor(nodeId: NodeId, regionId: RegionId): NpcZone? =
        store.findActiveByNode(nodeId) ?: store.findActiveByRegion(regionId)
}
