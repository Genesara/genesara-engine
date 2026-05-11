package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import java.util.UUID

internal inline fun dispatchChestTransfer(
    chestId: String,
    itemId: String,
    quantity: Int,
    world: WorldCommandGateway,
    engine: TickClock,
    buildCommand: (chestUuid: UUID, agent: AgentId) -> WorldCommand,
): ChestTransferResponse {
    val chestUuid = runCatching { UUID.fromString(chestId) }.getOrNull()
        ?: return ChestTransferResponse.rejected(
            chestId = chestId,
            itemId = itemId,
            quantity = quantity,
            reason = "bad_chest_id",
            detail = "chestId must be a UUID",
        )
    val agent = AgentContextHolder.current()
    val command = buildCommand(chestUuid, agent)
    val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
    return ChestTransferResponse.queued(
        commandId = command.commandId,
        appliesAtTick = appliesAtTick,
        chestId = chestUuid,
        itemId = itemId,
        quantity = quantity,
    )
}
