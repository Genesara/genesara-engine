package dev.gvart.genesara.api.internal.mcp.tools.craft

import java.util.UUID

data class CraftResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val recipeId: String,
)
