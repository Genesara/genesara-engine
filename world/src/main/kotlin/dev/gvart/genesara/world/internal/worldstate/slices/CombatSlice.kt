package dev.gvart.genesara.world.internal.worldstate.slices

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak

/**
 * Per-agent combat state (kill-streak window + dirty tracking).
 *
 * Owned by `:world-combat` once the zone split lands (ADR 0003). `dirtyKillStreaks`
 * lets `JooqWorldStateRepository.save` skip Redis writes on quiet ticks — without it,
 * every online agent's streak would be re-HSET every tick.
 */
internal data class CombatSlice(
    val killStreaks: Map<AgentId, AgentKillStreak>,
    val dirtyKillStreaks: Set<AgentId>,
) {
    companion object {
        val EMPTY = CombatSlice(
            killStreaks = emptyMap(),
            dirtyKillStreaks = emptySet(),
        )
    }
}
