package dev.gvart.genesara.api.internal.mcp.tools.consume

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class ConsumeTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "consume",
        description = "Consume one unit of a held item to refill the linked survival gauge. Queues a ConsumeItem command; the ItemConsumed event arrives on the event stream once the tick lands. Rejected if the agent doesn't own the item or the item isn't consumable.",
    )
    fun invoke(req: ConsumeRequest, toolContext: ToolContext): ConsumeResponse {
        touchActivity(toolContext, activity, "consume")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.ConsumeItem(agent = agent, item = ItemId(req.itemId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return ConsumeResponse.queued(command.commandId, nextTick, req.itemId)
    }
}
