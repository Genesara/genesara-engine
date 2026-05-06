package dev.gvart.genesara.api.internal.mcp.tools.build

import dev.gvart.genesara.world.BuildingType
import java.util.UUID

data class BuildResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
    val type: BuildingType,
)
