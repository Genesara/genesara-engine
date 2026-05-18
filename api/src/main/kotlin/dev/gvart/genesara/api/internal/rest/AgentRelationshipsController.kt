package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.relationships.GetRelationshipsResponse
import dev.gvart.genesara.api.internal.mcp.tools.relationships.RelationshipEntryView
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RelationshipsGateway
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/agents/{agentId}")
internal class AgentRelationshipsController(
    private val owned: OwnedAgentResolver,
    private val relationships: RelationshipsGateway,
    private val agents: AgentRegistry,
) {

    @GetMapping("/relationships")
    fun relationships(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): GetRelationshipsResponse {
        val agent = owned.resolve(player, agentId)
        val entries = relationships.scoresFor(agent.id).map { (other, row) ->
            RelationshipEntryView(
                agentId = PrefixedIds.encodeAgent(other),
                agentName = agents.find(other)?.name,
                score = row.score,
                lastChangedAtTick = row.lastChangedAtTick,
            )
        }
        return GetRelationshipsResponse(
            authority = agent.authority,
            fame = agent.fame,
            entries = entries,
        )
    }
}
