package dev.gvart.genesara.world.internal.worldstate.views

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak

/**
 * Typed read access to the combat slice (per-agent kill-streak window).
 */
interface CombatReadView {
    val killStreaks: Map<AgentId, AgentKillStreak>

    fun killStreakOf(agent: AgentId): AgentKillStreak = killStreaks[agent] ?: AgentKillStreak.EMPTY
}
