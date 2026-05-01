package dev.gvart.genesara.api.internal.mcp.tools.respawn

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
internal class RespawnTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "respawn",
        description = "Come back from the dead. You must be at HP=0 and not currently in " +
            "the world (the death sweep removed you on the tick your HP hit zero). Lands " +
            "you at your set safe node, then race-keyed starter node, then a random " +
            "spawnable node — in that order. Restores body to full pools. Queues a " +
            "Respawn command; the resulting AgentRespawned event arrives on your event " +
            "stream once the tick lands.",
    )
    fun invoke(req: RespawnRequest, toolContext: ToolContext): RespawnResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Respawn(agent = agent)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return RespawnResponse.queued(command.commandId, nextTick)
    }
}
