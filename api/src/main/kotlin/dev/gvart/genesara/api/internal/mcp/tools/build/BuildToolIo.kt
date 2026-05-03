package dev.gvart.genesara.api.internal.mcp.tools.build

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Spend one work step on a building type id at the agent's current node. The first call lays the " +
        "foundation (creates an UNDER_CONSTRUCTION instance and consumes the first step's " +
        "materials + stamina); subsequent calls advance an existing in-progress instance built " +
        "by this agent of this type on this node. The step that reaches the def's totalSteps " +
        "flips status to ACTIVE. Total step count and per-step cost vary per type — see " +
        "`inspect` on an in-progress building to read its progress and remaining cost.",
)
data class BuildRequest(
    @JsonPropertyDescription(
        "Building type id (e.g. CAMPFIRE, WORKBENCH, STORAGE_CHEST, SHELTER, FORGE, " +
            "ALCHEMY_TABLE, FARM_PLOT, WELL, WOODEN_WALL, ROAD, BRIDGE).",
    )
    val type: String,
)

/**
 * Response shape for `build`.
 *
 * - `kind = "queued"`: a BuildStructure command was queued; the result lands on `appliesAtTick`
 *   and arrives on the agent's event stream as `BuildingPlaced` (first step), `BuildingProgressed`
 *   (intermediate step) or `BuildingCompleted` (final step).
 * - `kind = "error"`: the requested type was not recognised. Reject at the boundary so a typo
 *   does not round-trip through the queue.
 */
data class BuildResponse(
    val kind: String,
    val type: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, type: String) =
            BuildResponse(kind = "queued", type = type, commandId = commandId, appliesAtTick = appliesAtTick)

        fun error(type: String, message: String) =
            BuildResponse(kind = "error", type = type, error = message)
    }
}
