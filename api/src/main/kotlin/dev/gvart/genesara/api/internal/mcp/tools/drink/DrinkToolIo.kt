package dev.gvart.genesara.api.internal.mcp.tools.drink

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class DrinkResponse(
    val kind: CommandAckKind,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            DrinkResponse(CommandAckKind.QUEUED, commandId, appliesAtTick)
    }
}
