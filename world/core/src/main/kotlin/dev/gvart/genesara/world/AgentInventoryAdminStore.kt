package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Admin-only direct mutation of an agent's stackable inventory.
 *
 * Bypasses the per-tick reducer pipeline — operators editing a live agent need
 * to write the row immediately, not enqueue a `WorldCommand`. The agent's
 * resident `WorldState` cache (loaded on spawn, see [JooqWorldStateRepository])
 * will re-read the row on the next session-resume; for currently-online agents
 * the next reducer run reads through to the saved row.
 */
interface AgentInventoryAdminStore {

    /**
     * Apply [delta] to the stack of [itemId] for [agentId]. Positive [delta] adds,
     * negative removes. Returns the post-write quantity (zero when fully removed).
     *
     * Returns [AdjustResult.Insufficient] when [delta] is negative and would push
     * the stack below zero — the row is left untouched.
     */
    fun adjust(agentId: AgentId, itemId: ItemId, delta: Int): AdjustResult

    /** Remove the entire stack of [itemId] for [agentId]. Returns the prior quantity (zero if absent). */
    fun removeAll(agentId: AgentId, itemId: ItemId): Int
}

sealed interface AdjustResult {
    data class Adjusted(val quantityAfter: Int) : AdjustResult
    data class Insufficient(val have: Int, val asked: Int) : AdjustResult
}
