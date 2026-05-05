package dev.gvart.genesara.api.internal.mcp.tools.harvest

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
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

data class HarvestResponse(
    val kind: CommandAckKind,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            HarvestResponse(CommandAckKind.QUEUED, itemId, commandId, appliesAtTick)
    }
}
