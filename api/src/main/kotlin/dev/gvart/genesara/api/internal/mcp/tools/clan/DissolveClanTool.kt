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
import org.springframework.stereotype.Component

@Component
internal class DissolveClanTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "dissolve_clan",
        description = "Dissolve your clan, releasing every member. Archon-only — other ranks are " +
            "rejected with NotClanArchon. All former members receive a `clan.dissolved` event. " +
            "This is irreversible; the clan name frees up for reuse. Rejections: NotInAnyClan, " +
            "NotClanArchon.",
    )
    fun invoke(toolContext: ToolContext): CommandAckResponse {
        touchActivity(toolContext, activity, "dissolve_clan")
        val agent = AgentContextHolder.current()
        return world.submitQueued(ClanCommand.DissolveClan(agent = agent), engine)
    }
}
