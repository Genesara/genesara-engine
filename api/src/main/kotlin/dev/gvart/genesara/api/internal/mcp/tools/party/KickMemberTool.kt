package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.SocialCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class KickMemberTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "kick_member",
        description = "Remove a party member. Leader-only — non-leaders are rejected with " +
            "NotPartyLeader. Self-kick is rejected with CannotKickSelf; use `leave_party` to " +
            "remove yourself. No line-of-sight required: the leader can kick an absent or AFK " +
            "member from anywhere. The kicked agent receives a `party.left` event with " +
            "reason=KICKED. If the post-removal size drops to one, the party auto-dissolves.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Wire-prefixed id of the agent to remove, e.g. `agent:<uuid>`.",
        )
        target: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "kick_member")
        val targetId = PrefixedIds.parseAgent(target)
            ?: return CommandAckResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be agent:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val command = SocialCommand.KickPartyMember(agent = agent, target = targetId)
        return world.submitQueued(command, engine, target = PrefixedIds.encodeAgent(targetId))
    }
}
