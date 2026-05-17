package dev.gvart.genesara.world.internal.worldstate.slices

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * Static world geometry + agent positions.
 *
 * Owned by `:world-core` once the zone split lands (ADR 0003). `regions` and
 * `nodes` are static-config; only `positions` mutates per tick, written by the
 * movement / spawn / unspawn / respawn / death reducers.
 */
data class CoreSlice(
    override val regions: Map<RegionId, Region>,
    override val nodes: Map<NodeId, Node>,
    override val positions: Map<AgentId, NodeId>,
) : CoreReadView {
    companion object {
        val EMPTY = CoreSlice(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
        )
    }
}
