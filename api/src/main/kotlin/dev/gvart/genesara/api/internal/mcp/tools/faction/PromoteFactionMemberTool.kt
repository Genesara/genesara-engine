package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class PromoteFactionMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "promote_faction_member",
        description = "Raise a faction member one faction rank (Pact→Speaker→Pillar). Sovereign-only; " +
            "Sovereign is not assignable (one per faction). The promoted agent's slot bonus updates and " +
            "they receive a `faction.rank_changed` event. Rejections: NotInAnyClan, NotInAnyFaction, " +
            "InsufficientFactionRank, FactionTargetNotMember, InvalidFactionRankAction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Faction member to promote — bare UUID or `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "promote_faction_member")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(target = target, reason = "bad_target_id", detail = "target must be a UUID or agent:<uuid>")
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            FactionCommand.PromoteFactionMember(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
