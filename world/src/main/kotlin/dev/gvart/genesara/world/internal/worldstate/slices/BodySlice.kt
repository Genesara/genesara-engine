package dev.gvart.genesara.world.internal.worldstate.slices

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory

/**
 * Per-agent body + inventory state.
 *
 * Owned by `:world-body` once the zone split lands (ADR 0003). Mutated by body,
 * death, passive, equipment, inventory, drink, consume, pickup reducers; read
 * by combat (for damage application) and economy (for stamina checks).
 */
internal data class BodySlice(
    val bodies: Map<AgentId, AgentBody>,
    val inventories: Map<AgentId, AgentInventory>,
) {
    companion object {
        val EMPTY = BodySlice(
            bodies = emptyMap(),
            inventories = emptyMap(),
        )
    }
}
