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
internal class PromoteClanMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "promote_clan_member",
        description = "Raise a clan member one rank (Initiate→Sworn→Bound→Vanguard). Requires Vanguard " +
            "rank or higher, and the resulting rank must stay strictly below your own — so only an " +
            "Archon can promote someone to Vanguard. Archon is not assignable here; use " +
            "`transfer_clan_leadership`. The promoted member receives a `clan.rank_changed` event. " +
            "Rejections: NotInAnyClan, InsufficientClanRank, TargetNotClanMember, InvalidClanRankAction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Member to promote — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "promote_clan_member")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be a UUID or agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            ClanCommand.PromoteClanMember(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
