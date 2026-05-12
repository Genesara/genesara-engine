package dev.gvart.genesara.api.internal.mcp.tools.trade

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class TradeOfferResponse(
    val kind: CommandAckKind,
    val tradeId: String? = null,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, tradeId: UUID) = TradeOfferResponse(
            kind = CommandAckKind.QUEUED,
            tradeId = tradeId.toString(),
            commandId = commandId,
            appliesAtTick = appliesAtTick,
        )

        fun rejected(reason: String, detail: String) = TradeOfferResponse(
            kind = CommandAckKind.REJECTED,
            reason = reason,
            detail = detail,
        )
    }
}

data class TradeRespondResponse(
    val kind: CommandAckKind,
    /** Echo of the input tradeId. Null only on a malformed-id reject (no UUID to echo). */
    val tradeId: String? = null,
    val accept: Boolean,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, tradeId: UUID, accept: Boolean) = TradeRespondResponse(
            kind = CommandAckKind.QUEUED,
            tradeId = tradeId.toString(),
            accept = accept,
            commandId = commandId,
            appliesAtTick = appliesAtTick,
        )

        fun rejected(tradeId: String?, accept: Boolean, reason: String, detail: String) = TradeRespondResponse(
            kind = CommandAckKind.REJECTED,
            tradeId = tradeId,
            accept = accept,
            reason = reason,
            detail = detail,
        )
    }
}
