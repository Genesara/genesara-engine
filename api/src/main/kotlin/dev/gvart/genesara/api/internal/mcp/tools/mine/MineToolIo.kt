package dev.gvart.genesara.api.internal.mcp.tools.mine

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Extract a mineral resource from the agent's current node. The agent must be in the world, " +
        "the requested item must be a mining-skill resource (STONE, ORE, COAL, GEM, SALT, CLAY, " +
        "PEAT, SAND), and the node's terrain must list the item among its deposits. Costs stamina.",
)
data class MineRequest(
    @JsonPropertyDescription("Item id to mine (e.g. STONE, ORE, COAL, GEM, SALT, CLAY, PEAT, SAND).")
    val itemId: String,
)

/**
 * Response shape for `mine`.
 *
 * - `kind = "queued"`: a MineResource command was queued; the result lands on `appliesAtTick`
 *   and shows up via the agent's event stream as a `ResourceGathered` notification.
 */
data class MineResponse(
    val kind: String,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            MineResponse("queued", itemId, commandId, appliesAtTick)
    }
}
