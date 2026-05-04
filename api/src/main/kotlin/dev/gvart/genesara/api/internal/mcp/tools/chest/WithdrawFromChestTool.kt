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
internal class WithdrawFromChestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "withdraw_from_chest",
        description = "Withdraw items from a STORAGE_CHEST back into the agent's inventory. The chest must be " +
            "ACTIVE, owned by the agent, and on the agent's current node. Rejected if the chest holds fewer " +
            "than the requested quantity of the item.",
    )
    fun invoke(req: ChestTransferRequest, toolContext: ToolContext): ChestTransferResponse {
        touchActivity(toolContext, activity, "withdraw_from_chest")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.WithdrawFromChest(
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
