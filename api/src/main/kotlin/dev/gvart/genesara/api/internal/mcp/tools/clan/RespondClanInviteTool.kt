package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.ClanCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class RespondClanInviteTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "respond_clan_invite",
        description = "Accept or decline a pending clan invite addressed to you (use the invite_id " +
            "from the `clan.invite_received` event). On accept you join as an Initiate, provided you " +
            "haven't joined another clan and the clan still has room. Stale or re-targeted invites " +
            "resolve as ClanInviteVoid. Rejections: ClanInviteNotFound, NotClanInvitee, ClanInviteVoid.",
    )
    fun invoke(
        @ToolParam(required = true, description = "The invite id (UUID) from the clan.invite_received event.")
        inviteId: String,
        @ToolParam(required = true, description = "true to accept and join, false to decline.")
        accept: Boolean,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "respond_clan_invite")
        val parsed = runCatching { UUID.fromString(inviteId.trim()) }.getOrNull()
            ?: return CommandAckResponse.rejected(
                target = inviteId,
                reason = "bad_invite_id",
                detail = "invite_id must be a UUID",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            ClanCommand.RespondClanInvite(agent = agent, inviteId = parsed, accept = accept),
            engine,
        )
    }
}
