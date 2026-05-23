package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import java.util.UUID

internal inline fun dispatchChestTransfer(
    chestId: String,
    world: WorldCommandGateway,
    engine: TickClock,
    buildCommand: (chestUuid: UUID, agent: AgentId) -> WorldCommand,
): CommandAckResponse {
    val chestUuid = runCatching { UUID.fromString(chestId) }.getOrNull()
        ?: return CommandAckResponse.rejected(
            target = chestId,
            reason = "bad_chest_id",
            detail = "chestId must be a UUID",
        )
    val agent = AgentContextHolder.current()
    val command = buildCommand(chestUuid, agent)
    return world.submitQueued(command, engine, target = chestId)
}
