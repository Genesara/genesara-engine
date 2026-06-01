package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitAgentTargeted
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.ClanCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * The clan member-management verbs that act on a single agent target. Grouped into one tool object
 * because they share the parse-target → queue shape (via [submitAgentTargeted]); kept distinct from
 * the membership *flow* tools (create/leave/dissolve/invite/respond) which have different signatures.
 */
@Component
internal class ClanMembershipTools(
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
    fun kick(
        @ToolParam(required = true, description = "Member to remove — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "kick_clan_member")
        val agent = AgentContextHolder.current()
        return world.submitAgentTargeted(target, engine) { ClanCommand.KickClanMember(agent, it) }
    }

    @Tool(
        name = "promote_clan_member",
        description = "Raise a clan member one rank (Initiate→Sworn→Bound→Vanguard). Requires Vanguard " +
            "rank or higher, and the resulting rank must stay strictly below your own — so only an " +
            "Archon can promote someone to Vanguard. Archon is not assignable here; use " +
            "`transfer_clan_leadership`. The promoted member receives a `clan.rank_changed` event. " +
            "Rejections: NotInAnyClan, InsufficientClanRank, TargetNotClanMember, InvalidClanRankAction.",
    )
    fun promote(
        @ToolParam(required = true, description = "Member to promote — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "promote_clan_member")
        val agent = AgentContextHolder.current()
        return world.submitAgentTargeted(target, engine) { ClanCommand.PromoteClanMember(agent, it) }
    }

    @Tool(
        name = "demote_clan_member",
        description = "Lower a clan member one rank. Requires Vanguard rank or higher, and the target " +
            "must already rank strictly below you; cannot demote below Initiate. The member receives a " +
            "`clan.rank_changed` event. Rejections: NotInAnyClan, InsufficientClanRank, " +
            "TargetNotClanMember, InvalidClanRankAction.",
    )
    fun demote(
        @ToolParam(required = true, description = "Member to demote — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "demote_clan_member")
        val agent = AgentContextHolder.current()
        return world.submitAgentTargeted(target, engine) { ClanCommand.DemoteClanMember(agent, it) }
    }

    @Tool(
        name = "transfer_clan_leadership",
        description = "Hand off Archon (clan leadership) to another member of your clan; you step " +
            "down to Vanguard. Archon-only — other ranks are rejected with NotClanArchon. The " +
            "target must be a member of your clan. Use this before leaving a clan you still lead. " +
            "Both you and the new Archon receive a `clan.rank_changed` event. Rejections: NotInAnyClan, " +
            "NotClanArchon, TransferTargetNotClanMember.",
    )
    fun transfer(
        @ToolParam(required = true, description = "Member to promote to Archon — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "transfer_clan_leadership")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(target, "bad_target_id", "target must be a UUID or agent:<uuid>")
        val agent = AgentContextHolder.current()
        if (targetId == agent) {
            return CommandAckResponse.rejected(PrefixedIds.encodeAgent(targetId), "cannot_transfer_to_self", "you are already the Archon")
        }
        return world.submitQueued(
            ClanCommand.TransferClanLeadership(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
