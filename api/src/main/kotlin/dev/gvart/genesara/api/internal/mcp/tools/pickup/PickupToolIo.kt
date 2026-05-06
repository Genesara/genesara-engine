package dev.gvart.genesara.api.internal.mcp.tools.pickup

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class PickupResponse(
    val kind: CommandAckKind,
    val dropId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, dropId: String) =
            PickupResponse(CommandAckKind.QUEUED, dropId, commandId, appliesAtTick)
    }
}
