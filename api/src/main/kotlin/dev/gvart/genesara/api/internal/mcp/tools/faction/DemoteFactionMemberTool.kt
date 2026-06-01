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
internal class DemoteFactionMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "demote_faction_member",
        description = "Lower a faction member one faction rank (toward Pact). Sovereign-only; cannot " +
            "demote below Pact. The agent's slot bonus updates and they receive a `faction.rank_changed` " +
            "event. Rejections: NotInAnyClan, NotInAnyFaction, InsufficientFactionRank, " +
            "FactionTargetNotMember, InvalidFactionRankAction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Faction member to demote — bare UUID or `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "demote_faction_member")
        val targetId = PrefixedIds.parseAgentLenient(target)
            ?: return CommandAckResponse.rejected(target = target, reason = "bad_target_id", detail = "target must be a UUID or agent:<uuid>")
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            FactionCommand.DemoteFactionMember(agent = agent, target = targetId),
            engine,
            target = PrefixedIds.encodeAgent(targetId),
        )
    }
}
