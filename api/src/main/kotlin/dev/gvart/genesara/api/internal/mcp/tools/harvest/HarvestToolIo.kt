package dev.gvart.genesara.api.internal.mcp.tools.harvest

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Extract a resource from the agent's current node. Covers every harvestable item " +
        "(wood, berries, herbs, stone, ore, coal, gem, salt, clay, peat, sand, …). The " +
        "agent must be in the world and the node's terrain must list the requested item " +
        "among its deposits or gatherables. Costs stamina; the resulting ResourceHarvested " +
        "event arrives on the agent's event stream once the tick lands.",
)
data class HarvestRequest(
    @JsonPropertyDescription("Item id to harvest (e.g. WOOD, BERRY, HERB, STONE, ORE, COAL, GEM, SALT, CLAY).")
    val itemId: String,
)

/**
 * Response shape for `harvest`.
 *
 * - `kind = "queued"`: a Harvest command was queued; the result lands on `appliesAtTick`
 *   and shows up via the agent's event stream as a `resource.harvested` notification.
 */
data class HarvestResponse(
    val kind: String,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            HarvestResponse("queued", itemId, commandId, appliesAtTick)
    }
}
