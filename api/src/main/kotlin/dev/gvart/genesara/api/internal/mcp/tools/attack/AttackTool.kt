package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class AttackTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "attack",
        description = "Attack another agent within your weapon's range — same node for melee, " +
            "adjacent or further nodes for ranged weapons (per the weapon's `range`). Queues an " +
            "AttackTarget command; the resulting AgentAttacked event arrives on the agent's event " +
            "stream once the tick lands. Costs stamina; rejected if the target is beyond range, " +
            "not in the world, or already dead.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Attack target — UUID of the agent to attack.")
        targetAgentId: String,
        toolContext: ToolContext,
    ): AttackResponse {
        touchActivity(toolContext, activity, "attack")
        val targetUuid = runCatching { UUID.fromString(targetAgentId) }.getOrNull()
            ?: return AttackResponse.rejected(
                targetAgentId = targetAgentId,
                reason = "bad_target_agent_id",
                detail = "targetAgentId must be a UUID",
            )
        val agent = AgentContextHolder.current()
        val command = WorldCommand.AttackTarget(agent = agent, target = AgentId(targetUuid))
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return AttackResponse.queued(command.commandId, appliesAtTick, targetUuid)
    }
}
