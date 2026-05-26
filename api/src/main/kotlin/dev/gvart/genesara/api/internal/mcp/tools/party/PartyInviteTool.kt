package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.SocialCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class PartyInviteTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "party_invite",
        description = "Invite one or more agents to your party. You must be solo or already the " +
            "leader of an existing party — non-leader members cannot invite. Each invitee must be " +
            "spawned, not already in a party, and inside your current line-of-sight (vision-gated). " +
            "Party cap is 6 total; current members + pending invites + new invitees must not exceed " +
            "it. Re-issuing an invite to the same invitee refreshes the TTL without emitting a new " +
            "event. Each successful invitee receives a `party.invite_received` event with the " +
            "invite_id they pass back via `party_respond`. Rejections: CannotPartyWithSelf, " +
            "NotInWorld, InviteeNotInWorld, InviteeNotInSight, NotPartyLeader, InviteeAlreadyInParty, " +
            "PartyCapacityExceeded.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Agent ids to invite — comma-separated bare UUIDs or wire-prefixed `agent:<uuid>` values " +
                "(e.g. `agent:abc-123,agent:def-456` or `abc-123,def-456`). Pass at least one.",
        )
        invitees: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "party_invite")
        val parts = invitees.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return CommandAckResponse.rejected(
                target = invitees,
                reason = "empty_invitees",
                detail = "invitees must contain at least one agent:<uuid>",
            )
        }
        // Tool-boundary cap so a malformed batch can't even reach the reducer.
        // The reducer enforces the actual cap (members + pending + new ≤ partyMaxSize);
        // this is an upper-bound on a single call's payload to keep input parsing bounded.
        if (parts.size > MAX_INVITEES_PER_CALL) {
            return CommandAckResponse.rejected(
                target = invitees,
                reason = "too_many_invitees",
                detail = "invitees per call is capped at $MAX_INVITEES_PER_CALL",
            )
        }
        val parsed = mutableListOf<AgentId>()
        for (raw in parts) {
            val id = PrefixedIds.parseAgentLenient(raw)
                ?: return CommandAckResponse.rejected(
                    target = raw,
                    reason = "bad_invitee_id",
                    detail = "each entry must be a UUID or agent:<uuid>",
                )
            parsed += id
        }
        val agent = AgentContextHolder.current()
        val command = SocialCommand.PartyInvite(agent = agent, invitees = parsed)
        val echoed = parsed.joinToString(",") { PrefixedIds.encodeAgent(it) }
        return world.submitQueued(command, engine, target = echoed)
    }

    private companion object {
        // Bounded by the cap (6) — a single call cannot request more invitees than
        // a full empty party could accept anyway.
        const val MAX_INVITEES_PER_CALL = 6
    }
}
