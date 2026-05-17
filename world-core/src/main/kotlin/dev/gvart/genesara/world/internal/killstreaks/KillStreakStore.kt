package dev.gvart.genesara.world.internal.killstreaks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak

internal interface KillStreakStore {

    fun byAgents(agents: Set<AgentId>): Map<AgentId, AgentKillStreak>

    fun save(agent: AgentId, streak: AgentKillStreak)

    fun delete(agent: AgentId)
}
