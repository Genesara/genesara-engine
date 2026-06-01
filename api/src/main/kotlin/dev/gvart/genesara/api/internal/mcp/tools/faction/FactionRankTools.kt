package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitAgentTargeted
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/** The Sovereign-only faction rank verbs (promote / demote); grouped via the shared submit helper. */
@Component
internal class FactionRankTools(
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
    fun promote(
        @ToolParam(required = true, description = "Faction member to promote — bare UUID or `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "promote_faction_member")
        val agent = AgentContextHolder.current()
        return world.submitAgentTargeted(target, engine) { FactionCommand.PromoteFactionMember(agent, it) }
    }

    @Tool(
        name = "demote_faction_member",
        description = "Lower a faction member one faction rank (toward Pact). Sovereign-only; cannot " +
            "demote below Pact. The agent's slot bonus updates and they receive a `faction.rank_changed` " +
            "event. Rejections: NotInAnyClan, NotInAnyFaction, InsufficientFactionRank, " +
            "FactionTargetNotMember, InvalidFactionRankAction.",
    )
    fun demote(
        @ToolParam(required = true, description = "Faction member to demote — bare UUID or `agent:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "demote_faction_member")
        val agent = AgentContextHolder.current()
        return world.submitAgentTargeted(target, engine) { FactionCommand.DemoteFactionMember(agent, it) }
    }
}
