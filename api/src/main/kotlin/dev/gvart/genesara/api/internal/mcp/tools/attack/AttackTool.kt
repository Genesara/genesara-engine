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
        description = "Attack another agent on your current node. Queues an AttackTarget command; " +
            "the resulting AgentAttacked event arrives on the agent's event stream once the tick lands. " +
            "Costs stamina; rejected if the target is not in the world, on a different node, or already dead.",
    )
    fun invoke(req: AttackRequest, toolContext: ToolContext): AttackResponse {
        touchActivity(toolContext, activity, "attack")
        val agent = AgentContextHolder.current()
        val target = AgentId(UUID.fromString(req.targetAgentId))
        val command = WorldCommand.AttackTarget(agent = agent, target = target)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return AttackResponse.queued(command.commandId, nextTick, req.targetAgentId)
    }
}
