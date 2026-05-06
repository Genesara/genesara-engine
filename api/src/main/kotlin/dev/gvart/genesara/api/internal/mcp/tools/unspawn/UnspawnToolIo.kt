package dev.gvart.genesara.api.internal.mcp.tools.unspawn

import java.util.UUID

data class UnspawnResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
