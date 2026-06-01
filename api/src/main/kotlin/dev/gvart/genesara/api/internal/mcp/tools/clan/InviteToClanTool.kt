package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.ClanCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class InviteToClanTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "invite_to_clan",
        description = "Invite an agent to your clan. Requires Bound rank or higher. NOT vision-gated " +
            "— you may invite any agent by id regardless of location. The invitee must not already be " +
            "in a clan, and your clan must have room under its member cap (members + pending invites + " +
            "1 ≤ cap). The invitee receives a `clan.invite_received` event with the invite_id they " +
            "pass to `respond_clan_invite`. Rejections: NotInAnyClan, InsufficientClanRank, " +
            "InviteeAlreadyInClan, ClanFull.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Agent to invite — bare UUID or wire-prefixed `agent:<uuid>`.")
        invitee: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "invite_to_clan")
        val inviteeId = PrefixedIds.parseAgentLenient(invitee)
            ?: return CommandAckResponse.rejected(
                target = invitee,
                reason = "bad_invitee_id",
                detail = "invitee must be a UUID or agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            ClanCommand.InviteToClan(agent = agent, invitee = inviteeId),
            engine,
            target = PrefixedIds.encodeAgent(inviteeId),
        )
    }
}
