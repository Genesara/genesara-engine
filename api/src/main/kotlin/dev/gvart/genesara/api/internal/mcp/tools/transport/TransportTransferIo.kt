package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

internal data class TransportTransferResponse(
    val kind: CommandAckKind,
    /** On QUEUED: the wire-prefixed `mount:<uuid>` re-encoded from the parsed id. On REJECTED: raw input echoed. */
    val target: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, target: String): TransportTransferResponse =
            TransportTransferResponse(
                kind = CommandAckKind.QUEUED,
                target = target,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(target: String, reason: String, detail: String? = null): TransportTransferResponse =
            TransportTransferResponse(
                kind = CommandAckKind.REJECTED,
                target = target,
                reason = reason,
                detail = detail,
            )
    }
}
