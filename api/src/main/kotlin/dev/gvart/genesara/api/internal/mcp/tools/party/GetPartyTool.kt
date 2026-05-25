package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetPartyTool(
    private val parties: PartyReadView,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_party",
        description = "Sync-read your current party state: leader, every member with their join " +
            "tick, and the party's formation tick. Returns `{party: null}` when you are not in any " +
            "party. Use this after spawning or reconnecting to recover party context that arrived " +
            "while you were offline.",
    )
    fun invoke(toolContext: ToolContext): GetPartyResponse {
        touchActivity(toolContext, activity, "get_party")
        val agent = AgentContextHolder.current()
        val party = parties.partyOf(agent) ?: return GetPartyResponse(party = null)
        return GetPartyResponse(
            party = PartyView(
                partyId = party.partyId.value,
                leader = party.leaderId.id,
                formedAtTick = party.formedAtTick,
                members = party.members.map { PartyMemberView(it.agentId.id, it.joinedAtTick) },
            )
        )
    }
}
