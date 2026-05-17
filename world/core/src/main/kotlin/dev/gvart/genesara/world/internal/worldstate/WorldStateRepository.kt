package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId

interface WorldStateRepository {
    fun load(worldId: WorldId, onlineAgentIds: Set<AgentId>): WorldState
    fun save(worldId: WorldId, state: WorldState)
}
