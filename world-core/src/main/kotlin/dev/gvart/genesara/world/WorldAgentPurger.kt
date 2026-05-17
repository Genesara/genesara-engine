package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Synchronously removes every world-side row keyed by [AgentId]. Called by the player-management
 * REST surface when an agent is being permanently deleted. World tables intentionally lack an FK
 * back to `agents` (cross-module), so deletion needs an explicit purge rather than a CASCADE.
 */
interface WorldAgentPurger {
    fun purge(agent: AgentId)
}
