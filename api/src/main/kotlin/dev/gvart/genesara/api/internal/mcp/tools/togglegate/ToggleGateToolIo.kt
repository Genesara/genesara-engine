package dev.gvart.genesara.api.internal.mcp.tools.togglegate

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class ToggleGateResponse(
    val kind: CommandAckKind,
    val gateId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, gateId: UUID) =
            ToggleGateResponse(CommandAckKind.QUEUED, gateId.toString(), commandId, appliesAtTick)

        fun rejected(gateId: String, reason: String, detail: String) =
            ToggleGateResponse(CommandAckKind.REJECTED, gateId, reason = reason, detail = detail)
    }
}
