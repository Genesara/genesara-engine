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
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

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
    fun invoke(
        @ToolParam(required = true, description = "Building instance UUID of the target chest (from look_around / inspect).")
        chestId: String,
        @ToolParam(required = true, description = "Item id to deposit (e.g. WOOD, STONE, BERRY).")
        itemId: String,
        @ToolParam(required = true, description = "Quantity to deposit. Must be > 0.")
        quantity: Int,
        toolContext: ToolContext,
    ): ChestTransferResponse {
        touchActivity(toolContext, activity, "deposit_to_chest")
        val chestUuid = runCatching { UUID.fromString(chestId) }.getOrNull()
            ?: return ChestTransferResponse.rejected(
                chestId = chestId,
                itemId = itemId,
                quantity = quantity,
                reason = "bad_chest_id",
                detail = "chestId must be a UUID",
            )
        val agent = AgentContextHolder.current()
        val command = WorldCommand.DepositToChest(
            agent = agent,
            chestId = chestUuid,
            item = ItemId(itemId),
            quantity = quantity,
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return ChestTransferResponse.queued(
            commandId = command.commandId,
            appliesAtTick = appliesAtTick,
            chestId = chestUuid,
            itemId = itemId,
            quantity = quantity,
        )
    }
}
