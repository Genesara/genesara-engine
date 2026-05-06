package dev.gvart.genesara.api.internal.mcp.tools.move

import java.util.UUID

data class MoveResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
