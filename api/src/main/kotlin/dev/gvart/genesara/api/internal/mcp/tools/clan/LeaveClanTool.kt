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
internal class LeaveClanTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "leave_clan",
        description = "Leave your current clan. If you are the sole Archon and the clan still has " +
            "other members, you must hand off leadership first via `transfer_clan_leadership` " +
            "(rejected with MustHandOffLeadership otherwise). An Archon who is the last remaining " +
            "member dissolves the clan by leaving. Rejections: NotInAnyClan, MustHandOffLeadership.",
    )
    fun invoke(toolContext: ToolContext): CommandAckResponse {
        touchActivity(toolContext, activity, "leave_clan")
        val agent = AgentContextHolder.current()
        return world.submitQueued(ClanCommand.LeaveClan(agent = agent), engine)
    }
}
