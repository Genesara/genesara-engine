package dev.gvart.genesara.api.internal.mcp.tools.move

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription("Move the agent to an adjacent node.")
data class MoveRequest(
    @JsonPropertyDescription("Target node id (must be adjacent to the agent's current node)")
    val nodeId: Long,
)

data class MoveResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
