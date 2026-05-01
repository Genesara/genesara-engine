package dev.gvart.genesara.api.internal.mcp.tools.safenode

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class SetSafeNodeTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "set_safe_node",
        description = "Bind your current node as your respawn checkpoint. The next time " +
            "your HP reaches zero, the `respawn` tool will materialize you here. Replaces " +
            "any prior checkpoint. Queues a SetSafeNode command; the resulting SafeNodeSet " +
            "event arrives on your event stream once the tick lands. " +
            "**Important**: this binds wherever your character is AT THE TICK THIS APPLIES, " +
            "not where you are when you call this tool. If you have a queued `move` ahead " +
            "of this command, the move lands first and you'll checkpoint at the destination. " +
            "Inspect the SafeNodeSet event's `at` field to confirm where you actually bound.",
    )
    fun invoke(req: SetSafeNodeRequest, toolContext: ToolContext): SetSafeNodeResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.SetSafeNode(agent = agent)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return SetSafeNodeResponse.queued(command.commandId, nextTick)
    }
}
