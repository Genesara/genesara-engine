package dev.gvart.genesara.world.internal.worldstate.slices

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId

/**
 * Static world geometry + agent positions.
 *
 * Owned by `:world-core` once the zone split lands (ADR 0003). `regions` and
 * `nodes` are static-config; only `positions` mutates per tick, written by the
 * movement / spawn / unspawn / respawn / death reducers.
 */
internal data class CoreSlice(
    val regions: Map<RegionId, Region>,
    val nodes: Map<NodeId, Node>,
    val positions: Map<AgentId, NodeId>,
) {
    companion object {
        val EMPTY = CoreSlice(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
        )
    }
}
