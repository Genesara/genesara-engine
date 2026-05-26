package dev.gvart.genesara.world

/** Node match beats region match; only [NpcZone.active] rows participate. */
interface NpcZoneLookup {
    fun resolveFor(nodeId: NodeId, regionId: RegionId): NpcZone?
}
