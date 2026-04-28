package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

interface AgentRegistrar {
    /**
     * Registers a new agent for [owner], assigns an opaque API token,
     * and creates a default [AgentProfile]. Returns the [Agent] including the token.
     */
    fun register(owner: PlayerId, name: String): Agent
}
