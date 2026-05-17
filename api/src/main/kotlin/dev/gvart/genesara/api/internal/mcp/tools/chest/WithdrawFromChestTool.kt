package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
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
    fun invoke(
        @ToolParam(required = true, description = "Building instance UUID of the target chest (from look_around / inspect).")
        chestId: String,
        @ToolParam(required = true, description = "Item id to withdraw (e.g. WOOD, STONE, BERRY).")
        itemId: String,
        @ToolParam(required = true, description = "Quantity to withdraw. Must be > 0.")
        quantity: Int,
        toolContext: ToolContext,
    ): ChestTransferResponse {
        touchActivity(toolContext, activity, "withdraw_from_chest")
        return dispatchChestTransfer(chestId, itemId, quantity, world, engine) { chestUuid, agent ->
            EnvironmentCommand.WithdrawFromChest(
                agent = agent,
                chestId = chestUuid,
                item = ItemId(itemId),
                quantity = quantity,
            )
        }
    }
}
