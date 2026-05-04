package dev.gvart.genesara.api.internal.mcp.tools.chest

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
internal class DepositToChestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "deposit_to_chest",
        description = "Deposit items from the agent's inventory into a STORAGE_CHEST. The chest must be " +
            "ACTIVE, owned by the agent, and on the agent's current node. The deposit is rejected if it " +
            "would push the chest above its weight cap or if the agent does not hold enough of the item.",
    )
    fun invoke(req: ChestTransferRequest, toolContext: ToolContext): ChestTransferResponse {
        touchActivity(toolContext, activity, "deposit_to_chest")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.DepositToChest(
            agent = agent,
            chestId = req.chestId,
            item = ItemId(req.itemId),
            quantity = req.quantity,
        )
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return ChestTransferResponse(
            commandId = command.commandId,
            appliesAtTick = nextTick,
            chestId = req.chestId,
            itemId = req.itemId,
            quantity = req.quantity,
        )
    }
}
