package dev.gvart.genesara.api.internal.mcp.tools.spawn

import java.util.UUID

/**
 * Response shape for `spawn`. A SpawnAgent command is queued; the resolved node lands
 * on the resulting `agent.spawned` event tagged with [commandId] as `causedBy`.
 */
data class SpawnResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
