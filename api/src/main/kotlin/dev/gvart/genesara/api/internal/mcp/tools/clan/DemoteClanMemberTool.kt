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
internal class DemoteClanMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "demote_clan_member",
        description = "Lower a clan member one rank. Requires Vanguard rank or higher, and the target " +
            "must already rank strictly below you; cannot demote below Initiate. The member receives a " +
            "`clan.rank_changed` event. Rejections: NotInAnyClan, InsufficientClanRank, " +
            "TargetNotClanMember, InvalidClanRankAction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Member to demote — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "demote_clan_member")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be a UUID or agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            ClanCommand.DemoteClanMember(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
