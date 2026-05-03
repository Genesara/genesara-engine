package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
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
        val chest = parseChestId(req)
            ?: return ChestTransferResponse.error(
                req.chestId, req.itemId, req.quantity,
                "chestId must be a UUID, got '${req.chestId}'",
            )
        val item = parseItemId(req)
            ?: return ChestTransferResponse.error(
                req.chestId, req.itemId, req.quantity,
                "itemId must be non-blank, got '${req.itemId}'",
            )
        if (req.quantity <= 0) {
            return ChestTransferResponse.error(
                req.chestId, req.itemId, req.quantity,
                "quantity must be > 0, got ${req.quantity}",
            )
        }
        val agent = AgentContextHolder.current()
        val command = WorldCommand.WithdrawFromChest(agent = agent, chestId = chest, item = item, quantity = req.quantity)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return ChestTransferResponse.queued(command.commandId, nextTick, chest.toString(), item.value, req.quantity)
    }
}
