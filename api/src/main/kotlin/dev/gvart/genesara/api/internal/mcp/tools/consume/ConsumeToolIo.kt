package dev.gvart.genesara.api.internal.mcp.tools.consume

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class ConsumeResponse(
    val kind: CommandAckKind,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            ConsumeResponse(CommandAckKind.QUEUED, itemId, commandId, appliesAtTick)
    }
}
