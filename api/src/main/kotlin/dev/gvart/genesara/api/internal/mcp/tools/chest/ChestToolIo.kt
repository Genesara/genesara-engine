package dev.gvart.genesara.api.internal.mcp.tools.chest

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Move items between the agent's inventory and a STORAGE_CHEST building owned by the agent. " +
        "The chest must be ACTIVE and on the agent's current node. Used by both `deposit_to_chest` " +
        "and `withdraw_from_chest`.",
)
data class ChestTransferRequest(
    @JsonPropertyDescription("Building instance UUID of the target chest (from look_around / inspect).")
    val chestId: UUID,
    @JsonPropertyDescription("Item id to transfer (e.g. WOOD, STONE, BERRY).")
    val itemId: String,
    @JsonPropertyDescription("Quantity to transfer. Must be > 0; the reducer rejects 0 / negative as NonPositiveQuantity.")
    val quantity: Int,
)

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
