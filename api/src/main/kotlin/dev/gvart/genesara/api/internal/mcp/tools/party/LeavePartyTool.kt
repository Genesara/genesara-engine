package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.SocialCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class LeavePartyTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "leave_party",
        description = "Exit your current party. If you are the leader of a party with two or more " +
            "remaining members, leadership transfers to the earliest-joined remaining member; if " +
            "the post-removal size is one, the party auto-dissolves. Leaving as leader sweeps " +
            "your pending invites with a `party.invite_cancelled` event to each invitee. " +
            "Rejections: NotInAnyParty.",
    )
    fun invoke(toolContext: ToolContext): CommandAckResponse {
        touchActivity(toolContext, activity, "leave_party")
        val agent = AgentContextHolder.current()
        val command = SocialCommand.LeaveParty(agent = agent)
        return world.submitQueued(command, engine)
    }
}
