package dev.gvart.genesara.api.internal.mcp.tools.drink

import com.fasterxml.jackson.annotation.JsonClassDescription
import java.util.UUID

@JsonClassDescription("Drink directly from a water-source terrain (coastal, river delta, wetlands, shoreline). Refills THIRST and costs a small amount of stamina. No item required; rejected on terrains without surface water.")
class DrinkRequest

/**
 * Response shape for `drink`.
 *
 * - `kind = "queued"`: a Drink command was queued; the resulting AgentDrank event arrives
 *   on the agent's event stream once the tick lands.
 */
data class DrinkResponse(
    val kind: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            DrinkResponse("queued", commandId, appliesAtTick)
    }
}
