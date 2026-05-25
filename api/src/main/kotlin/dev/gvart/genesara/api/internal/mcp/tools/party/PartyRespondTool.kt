package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.SocialCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class PartyRespondTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "party_respond",
        description = "Accept or decline a pending party invitation addressed to you. On accept " +
            "the resolver looks up the inviter's current party (creating one if the inviter is " +
            "solo) and appends you to its roster; you receive a `party.joined` event and every " +
            "current member is notified. On decline the inviter receives a `party.invite_declined`. " +
            "If the inviter's context shifted between send and accept (left their party, no longer " +
            "leader, party full) the invite resolves as PartyInviteVoid. Rejections: " +
            "PartyInviteNotFound (expired or already resolved), NotPartyInvitee, PartyInviteVoid.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Invite UUID from the `party.invite_received` event.",
        )
        inviteId: String,
        @ToolParam(
            required = true,
            description = "True to accept and join the party; false to decline.",
        )
        accept: Boolean,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "party_respond")
        val parsed = runCatching { UUID.fromString(inviteId) }.getOrNull()
            ?: return CommandAckResponse.rejected(
                target = inviteId,
                reason = "bad_invite_id",
                detail = "inviteId must be a UUID",
            )
        val agent = AgentContextHolder.current()
        val command = SocialCommand.PartyRespond(agent = agent, inviteId = parsed, accept = accept)
        return world.submitQueued(command, engine, target = parsed.toString())
    }
}
