package dev.gvart.genesara.api.internal.mcp.tools.spawn

import java.util.UUID

/**
 * Response shape for `spawn`. A SpawnAgent command is queued; the resolved node lands
 * on the resulting `agent.spawned` event tagged with [commandId] as `causedBy`.
 *
 * [initialLocation] and [initialHp] are the best-effort pre-tick projections of where
 * the agent will land and what HP they will have. These values are computed synchronously
 * so callers do not need to immediately call `get_status` after spawn — both fields come
 * from the same DB rows the world reducer will read. They can be null when the agent has
 * never spawned and no starter node is configured.
 */
data class SpawnResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val initialLocation: Long?,
    val initialHp: Int?,
)
