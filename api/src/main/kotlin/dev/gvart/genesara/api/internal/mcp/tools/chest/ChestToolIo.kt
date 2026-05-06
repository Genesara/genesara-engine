package dev.gvart.genesara.api.internal.mcp.tools.chest

import java.util.UUID

/**
 * Successful queue-and-ack response for chest transfers. The matching `ItemDeposited` /
 * `ItemWithdrawn` event lands on the agent's event stream once the tick resolves; the
 * reducer-level rejections (chest doesn't exist, capacity exceeded, etc.) surface via the
 * event stream as a rejection event, not in-band on this response.
 */
data class ChestTransferResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val chestId: UUID,
    val itemId: String,
    val quantity: Int,
)
