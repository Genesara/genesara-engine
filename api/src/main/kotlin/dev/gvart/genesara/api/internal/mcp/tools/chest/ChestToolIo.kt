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
    val chestId: String,
    @JsonPropertyDescription("Item id to transfer (e.g. WOOD, STONE, BERRY).")
    val itemId: String,
    @JsonPropertyDescription("Quantity to transfer. Must be > 0.")
    val quantity: Int,
)

/**
 * Response shape for chest transfers.
 *
 * - `kind = "queued"`: a Deposit/Withdraw command was queued; the result lands on `appliesAtTick`
 *   and arrives on the agent's event stream as `ItemDeposited` / `ItemWithdrawn`.
 * - `kind = "error"`: the request was rejected at the boundary (malformed UUID, blank item id,
 *   non-positive quantity). Reducer-level rejections (chest doesn't exist, capacity exceeded,
 *   etc.) surface via the event stream as a rejection event.
 */
data class ChestTransferResponse(
    val kind: String,
    val chestId: String,
    val itemId: String,
    val quantity: Int,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, chestId: String, itemId: String, quantity: Int) =
            ChestTransferResponse(
                kind = "queued",
                chestId = chestId,
                itemId = itemId,
                quantity = quantity,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun error(chestId: String, itemId: String, quantity: Int, message: String) =
            ChestTransferResponse(
                kind = "error",
                chestId = chestId,
                itemId = itemId,
                quantity = quantity,
                error = message,
            )
    }
}
