package dev.gvart.genesara.api.internal.mcp.tools.pickup

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class PickupTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "pickup",
        description = "Take a ground item off the agent's current node. The dropId comes from the " +
            "ground items list returned by `look_around`. Atomic with concurrent pickups — the first " +
            "agent on the same tick wins; later callers receive a GroundItemNoLongerAvailable rejection. " +
            "Stackable drops land in inventory; equipment drops land in the equipment store unequipped " +
            "(call `equip` separately to slot them).",
    )
    fun invoke(req: PickupRequest, toolContext: ToolContext): PickupResponse {
        touchActivity(toolContext, activity, "pickup")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Pickup(agent = agent, dropId = UUID.fromString(req.dropId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return PickupResponse.queued(command.commandId, nextTick, req.dropId)
    }
}
