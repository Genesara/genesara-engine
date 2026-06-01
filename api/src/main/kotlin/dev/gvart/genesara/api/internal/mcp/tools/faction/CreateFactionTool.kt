package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class CreateFactionTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "create_faction",
        description = "Found a faction with your clan as the first member; you (your clan's Archon) " +
            "become its Sovereign and your clan-mates become Pact members — which activates each " +
            "member's faction-rank skill-slot bonus. Archon-only; your clan must not already be in a " +
            "faction; the name must be unique. Rejections: NotInAnyClan, NotClanArchon, AlreadyInFaction, " +
            "FactionNameTaken.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Faction name, 1-64 characters, unique across the world.")
        name: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "create_faction")
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_FACTION_NAME) {
            return CommandAckResponse.rejected(
                target = name,
                reason = "bad_faction_name",
                detail = "name must be 1-$MAX_FACTION_NAME characters",
            )
        }
        val agent = AgentContextHolder.current()
        return world.submitQueued(FactionCommand.CreateFaction(agent = agent, name = trimmed), engine, target = trimmed)
    }

    private companion object {
        const val MAX_FACTION_NAME = 64
    }
}
