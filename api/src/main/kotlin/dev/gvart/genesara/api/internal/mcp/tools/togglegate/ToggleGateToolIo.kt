package dev.gvart.genesara.api.internal.mcp.tools.togglegate

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class ToggleGateResponse(
    val kind: CommandAckKind,
    val gateId: UUID,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, gateId: UUID) =
            ToggleGateResponse(CommandAckKind.QUEUED, gateId, commandId, appliesAtTick)
    }
}
