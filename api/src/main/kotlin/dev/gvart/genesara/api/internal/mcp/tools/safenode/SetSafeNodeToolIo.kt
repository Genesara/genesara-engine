package dev.gvart.genesara.api.internal.mcp.tools.safenode

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class SetSafeNodeResponse(
    val kind: CommandAckKind,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            SetSafeNodeResponse(CommandAckKind.QUEUED, commandId, appliesAtTick)
    }
}
