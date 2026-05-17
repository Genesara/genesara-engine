package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.AgentWorldRouter

class NoOpAgentWorldRouter(private val worldId: WorldId? = null) : AgentWorldRouter {
    override fun routeFor(agent: AgentId): WorldId? = worldId
}
