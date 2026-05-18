package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Resolves `(player, agentId)` to an [Agent] the player owns, or throws 404.
 * Returning 404 (rather than 403) when the agent exists but belongs to someone
 * else avoids leaking agent-id existence across accounts.
 */
@Component
internal class OwnedAgentResolver(private val agents: AgentRegistry) {

    fun resolve(player: Player, agentId: UUID): Agent {
        val agent = agents.find(AgentId(agentId))
        if (agent == null || agent.owner != player.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found")
        }
        return agent
    }
}
