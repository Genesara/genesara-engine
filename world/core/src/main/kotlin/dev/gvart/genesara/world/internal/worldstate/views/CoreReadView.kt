package dev.gvart.genesara.world.internal.worldstate.views

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId

/**
 * Typed read access to the core slice (static world + agent positions).
 *
 * Reducers in zones other than `:world-core` (once split) take this interface
 * rather than `CoreSlice` directly, so the compile-enforced boundary is
 * "you may *read* core, never write." Phase 1.3 sweeps the dependent reducers
 * to this shape.
 */
interface CoreReadView {
    val regions: Map<RegionId, Region>
    val nodes: Map<NodeId, Node>
    val positions: Map<AgentId, NodeId>
}
