package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class InviteClanToFactionTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "invite_clan_to_faction",
        description = "Invite another clan to join your faction. Requires Pillar or Sovereign faction " +
            "rank. The target clan (by clan id, e.g. from its members' get_clan_status) must not " +
            "already belong to a faction; its Archon receives a `faction.invite_received` event and " +
            "accepts via `respond_faction_invite`. Rejections: NotInAnyClan, NotInAnyFaction, " +
            "InsufficientFactionRank, TargetClanNotFound, TargetClanAlreadyInFaction.",
    )
    fun invoke(
        @ToolParam(required = true, description = "The clan id (UUID) to invite.")
        clanId: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "invite_clan_to_faction")
        val parsed = runCatching { UUID.fromString(clanId.trim()) }.getOrNull()
            ?: return CommandAckResponse.rejected(
                target = clanId,
                reason = "bad_clan_id",
                detail = "clan id must be a UUID",
            )
        val agent = AgentContextHolder.current()
        return world.submitQueued(
            FactionCommand.InviteClanToFaction(agent = agent, targetClanId = ClanId(parsed)),
            engine,
            target = parsed.toString(),
        )
    }
}
