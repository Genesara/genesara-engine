package dev.gvart.genesara.api.internal.mcp.tools.respawn

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class RespawnResponse(
    val kind: CommandAckKind,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            RespawnResponse(CommandAckKind.QUEUED, commandId, appliesAtTick)
    }
}
