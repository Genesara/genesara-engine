package dev.gvart.genesara.api.internal.mcp.tools.unspawn

import com.fasterxml.jackson.annotation.JsonClassDescription
import java.util.UUID

@JsonClassDescription("Logout: leave the world. Position is remembered for the next spawn.")
class UnspawnRequest

data class UnspawnResponse(
    val commandId: UUID,
    val appliesAtTick: Long,
)
