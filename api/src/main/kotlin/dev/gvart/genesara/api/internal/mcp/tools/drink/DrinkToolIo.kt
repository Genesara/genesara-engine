package dev.gvart.genesara.api.internal.mcp.tools.drink

import com.fasterxml.jackson.annotation.JsonClassDescription
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

@JsonClassDescription("Drink directly from a water-source terrain (coastal, river delta, wetlands, shoreline). Refills THIRST and costs a small amount of stamina. No item required; rejected on terrains without surface water.")
class DrinkRequest

data class DrinkResponse(
    val kind: CommandAckKind,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            DrinkResponse(CommandAckKind.QUEUED, commandId, appliesAtTick)
    }
}
