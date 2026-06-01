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
import org.springframework.stereotype.Component

@Component
internal class LeaveFactionTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "leave_faction",
        description = "As your clan's Archon, pull your clan out of its faction; every member's " +
            "faction rank (and its slot bonus) is cleared. If your clan was the faction's last member, " +
            "the faction dissolves. Rejections: NotInAnyClan, NotClanArchon, NotInAnyFaction.",
    )
    fun invoke(toolContext: ToolContext): CommandAckResponse {
        touchActivity(toolContext, activity, "leave_faction")
        val agent = AgentContextHolder.current()
        return world.submitQueued(FactionCommand.LeaveFaction(agent = agent), engine)
    }
}
