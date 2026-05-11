package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class ChestTransferResponse(
    val kind: CommandAckKind,
    val chestId: String,
    val itemId: String,
    val quantity: Int,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, chestId: UUID, itemId: String, quantity: Int) =
            ChestTransferResponse(
                kind = CommandAckKind.QUEUED,
                chestId = chestId.toString(),
                itemId = itemId,
                quantity = quantity,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(chestId: String, itemId: String, quantity: Int, reason: String, detail: String) =
            ChestTransferResponse(
                kind = CommandAckKind.REJECTED,
                chestId = chestId,
                itemId = itemId,
                quantity = quantity,
                reason = reason,
                detail = detail,
            )
    }
}
