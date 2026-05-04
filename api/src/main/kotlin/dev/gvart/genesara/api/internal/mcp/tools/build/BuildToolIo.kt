package dev.gvart.genesara.api.internal.mcp.tools.build

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.world.BuildingType
import java.util.UUID

@JsonClassDescription(
    "Spend one work step on a building type at the agent's current node. The first call lays the " +
        "foundation (creates an UNDER_CONSTRUCTION instance and consumes the first step's " +
        "materials + stamina); subsequent calls advance an existing in-progress instance built " +
        "by this agent of this type on this node. The step that reaches the def's totalSteps " +
        "flips status to ACTIVE. Total step count and per-step cost vary per type — see " +
        "`inspect` on an in-progress building to read its progress and remaining cost.",
)
data class BuildRequest(
    @JsonPropertyDescription(
        "Building type. Must be one of: CAMPFIRE, WORKBENCH, STORAGE_CHEST, SHELTER, FORGE, " +
            "ALCHEMY_TABLE, FARM_PLOT, WELL, WOODEN_WALL, ROAD, BRIDGE.",
    )
    val type: BuildingType,
)

/**
 * Successful queue-and-ack response for `build`. The matching `BuildingPlaced`
 * (first step), `BuildingProgressed` (intermediate step) or `BuildingCompleted`
 * (final step) event arrives on the agent's event stream once the tick lands.
 *
 * Malformed inputs (unknown type, missing field) surface via the standard MCP
 * tool-error path during Jackson deserialization, not as an in-band variant.
 */
data class BuildResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val type: BuildingType,
)
