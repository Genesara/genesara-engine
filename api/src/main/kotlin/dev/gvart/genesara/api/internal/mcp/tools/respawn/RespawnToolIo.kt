package dev.gvart.genesara.api.internal.mcp.tools.respawn

import com.fasterxml.jackson.annotation.JsonClassDescription
import java.util.UUID

@JsonClassDescription(
    "Materialize after death. You must currently be dead (HP=0 and not in the world). " +
        "Lands you at your set checkpoint, the race-keyed starter node, or a random " +
        "spawnable node — in that order. Restores HP / Stamina / Mana / gauges.",
)
class RespawnRequest

data class RespawnResponse(
    val kind: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            RespawnResponse("queued", commandId, appliesAtTick)
    }
}
