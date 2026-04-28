package dev.gvart.genesara.api.internal.mcp.tools.gather

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription("Gather a resource from the agent's current node. The agent must be in the world and the node's terrain must list the requested item among its gatherables. Costs stamina.")
data class GatherRequest(
    @JsonPropertyDescription("Item id to gather (e.g. WOOD, STONE, BERRY, HERB, ORE).")
    val itemId: String,
)

/**
 * Response shape for `gather`.
 *
 * - `kind = "queued"`: a GatherResource command was queued; the result lands on `appliesAtTick`
 *   and shows up via the agent's event stream as a `ResourceGathered` notification.
 */
data class GatherResponse(
    val kind: String,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            GatherResponse("queued", itemId, commandId, appliesAtTick)
    }
}
