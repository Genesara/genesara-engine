package dev.gvart.genesara.api.internal.mcp.tools.relationships

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RelationshipsGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * Read-only projection of the calling agent's per-pair relationship ledger
 * (mechanics-reference §11). Score runs −100..+100; mid-band 0 = neutral.
 * Only pairs the agent has actually interacted with (or been a witness to)
 * appear here — unfamiliar agents are implicit-neutral and not returned.
 * Plus the agent's own global Authority and Fame for context.
 */
@Component
internal class GetRelationshipsTool(
    private val relationships: RelationshipsGateway,
    private val agents: AgentRegistry,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_relationships",
        description = "Return the calling agent's per-pair relationship scores (the agents " +
            "they've traded with, witnessed attacking, etc.), plus their own Authority and Fame. " +
            "Each entry's `score` is in [-100, +100]; missing pairs are implicit-neutral (0). " +
            "Entries are sorted by score descending so the top of the list is the agent's " +
            "closest allies.",
    )
    fun invoke(toolContext: ToolContext): GetRelationshipsResponse {
        touchActivity(toolContext, activity, "get_relationships")
        val agentId = AgentContextHolder.current()
        val self = agents.find(agentId) ?: error("Agent not registered: $agentId")
        // scoresFor returns rows already sorted by score desc — no client-side re-sort.
        val entries = relationships.scoresFor(agentId).map { (other, row) ->
            RelationshipEntryView(
                agentId = PrefixedIds.encodeAgent(other),
                agentName = agents.find(other)?.name,
                score = row.score,
                lastChangedAtTick = row.lastChangedAtTick,
            )
        }
        return GetRelationshipsResponse(
            authority = self.authority,
            fame = self.fame,
            entries = entries,
        )
    }
}
