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
internal class TransferClanLeadershipTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "transfer_clan_leadership",
        description = "Hand off Archon (clan leadership) to another member of your clan; you step " +
            "down to Vanguard. Archon-only — other ranks are rejected with NotClanArchon. The " +
            "target must be a member of your clan. Use this before leaving a clan you still lead. " +
            "Both you and the new Archon receive a `clan.rank_changed` event. Rejections: NotInAnyClan, " +
            "NotClanArchon, TransferTargetNotClanMember.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Member to promote to Archon — bare UUID or wire-prefixed `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "transfer_clan_leadership")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be a UUID or agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        if (targetId == agent) {
            return CommandAckResponse.rejected(
                target = PrefixedIds.encodeAgent(targetId),
                reason = "cannot_transfer_to_self",
                detail = "you are already the Archon",
            )
        }
        return world.submitQueued(
            ClanCommand.TransferClanLeadership(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
