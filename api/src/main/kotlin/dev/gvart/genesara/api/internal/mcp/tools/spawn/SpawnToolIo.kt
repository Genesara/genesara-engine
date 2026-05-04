package dev.gvart.genesara.api.internal.mcp.tools.spawn

import com.fasterxml.jackson.annotation.JsonClassDescription
import java.util.UUID

@JsonClassDescription("Login: enter the world. The simulation picks the destination; the resolved node arrives on the agent.spawned event.")
class SpawnRequest

/**
 * Response shape for `spawn`. A SpawnAgent command is queued; the resolved node lands
 * on the resulting `agent.spawned` event tagged with [commandId] as `causedBy`.
 */
data class SpawnResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
