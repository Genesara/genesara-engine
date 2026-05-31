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
internal class KickClanMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "kick_clan_member",
        description = "Remove a member from your clan. Requires Vanguard rank or higher, and the " +
            "target must rank strictly below you. Self-kick is rejected — use `leave_clan`. The " +
            "kicked agent receives a `clan.left` event with reason=KICKED. Rejections: NotInAnyClan, " +
            "InsufficientClanRank, TargetNotClanMember, CannotKickSelfFromClan, InvalidClanRankAction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Member to remove — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "kick_clan_member")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be a UUID or agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            ClanCommand.KickClanMember(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
