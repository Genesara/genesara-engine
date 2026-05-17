package dev.gvart.genesara.world.internal.worldstate.slices

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.internal.worldstate.views.EnvironmentReadView

/**
 * NPCs in the active-set + per-tick dirty tracking.
 *
 * Owned by `:world-environment` once the zone split lands (ADR 0003).
 *
 * Loaded fresh each tick from [dev.gvart.genesara.world.NpcsStore.byNodes]
 * (R=8 hops from any online agent's position). Mutations during the tick
 * (damage, flee, last-attack-tick advance) live here and flush at save time
 * via [dirtyNpcs] / [removedNpcs]. [nodesClearedThisTick] advances each node's
 * `last_cleared_tick` so the lazy-spawn timer starts when the node actually
 * emptied.
 */
internal data class EnvironmentSlice(
    override val npcs: Map<NpcId, Npc>,
    val dirtyNpcs: Set<NpcId>,
    val removedNpcs: Set<NpcId>,
    val nodesClearedThisTick: Map<NodeId, Long>,
) : EnvironmentReadView {
    companion object {
        val EMPTY = EnvironmentSlice(
            npcs = emptyMap(),
            dirtyNpcs = emptySet(),
            removedNpcs = emptySet(),
            nodesClearedThisTick = emptyMap(),
        )
    }
}
