package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class RespondFactionInviteTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "respond_faction_invite",
        description = "As your clan's Archon, accept or decline a pending faction invite (invite_id " +
            "from the `faction.invite_received` event). On accept your whole clan joins the faction at " +
            "Pact rank, activating each member's faction-rank slot bonus. Rejections: " +
            "FactionInviteNotFound, NotFactionInvitee, FactionInviteVoid.",
    )
    fun invoke(
        @ToolParam(required = true, description = "The invite id (UUID) from the faction.invite_received event.")
        inviteId: String,
        @ToolParam(required = true, description = "true to accept and join, false to decline.")
        accept: Boolean,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "respond_faction_invite")
        val parsed = runCatching { UUID.fromString(inviteId.trim()) }.getOrNull()
            ?: return CommandAckResponse.rejected(
                target = inviteId,
                reason = "bad_invite_id",
                detail = "invite_id must be a UUID",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            FactionCommand.RespondFactionInvite(agent = agent, inviteId = parsed, accept = accept),
            engine,
        )
    }
}
