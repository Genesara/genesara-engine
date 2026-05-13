package dev.gvart.genesara.api.internal.mcp.tools.cultivation

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class PlantResponse(
    val kind: CommandAckKind,
    val plotId: String,
    val cropId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, plotId: String, cropId: String) =
            PlantResponse(CommandAckKind.QUEUED, plotId, cropId, commandId, appliesAtTick)
    }
}

data class TendResponse(
    val kind: CommandAckKind,
    val plotId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, plotId: String) =
            TendResponse(CommandAckKind.QUEUED, plotId, commandId, appliesAtTick)
    }
}
