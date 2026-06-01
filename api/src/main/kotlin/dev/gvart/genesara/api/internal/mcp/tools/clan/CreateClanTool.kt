package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.ClanCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class CreateClanTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "create_clan",
        description = "Found a new clan; you become its Archon (the top rank). No co-location, " +
            "structures, or base are required — clan creation is a pure social act. You must not " +
            "already be in a clan (one clan per agent). The name must be unique across the world. " +
            "After founding, invite members up to the clan's cap (6 while the clan owns no base; " +
            "it grows as the clan captures territory). Rejections: AlreadyInClan, ClanNameTaken.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Clan name, 1-64 characters, unique across the world.")
        name: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "create_clan")
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CLAN_NAME) {
            return CommandAckResponse.rejected(
                target = name,
                reason = "bad_clan_name",
                detail = "name must be 1-$MAX_CLAN_NAME characters",
            )
        }
        val agent = AgentContextHolder.current()
        return world.submitQueued(ClanCommand.CreateClan(agent = agent, name = trimmed), engine, target = trimmed)
    }

    private companion object {
        const val MAX_CLAN_NAME = 64
    }
}
