package dev.gvart.genesara.api.internal.mcp.tools.pickup

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

@JsonClassDescription(
    "Take a ground item off the agent's current node. The dropId comes from the ground " +
        "items list in the `look_around` response. The agent must be standing on the node " +
        "where the drop sits. Concurrent pickups on the same tick are atomic — only the first " +
        "caller wins; the second receives a GroundItemNoLongerAvailable rejection.",
)
data class PickupRequest(
    @JsonPropertyDescription("Drop id (UUID) from look_around's groundItems entry.")
    val dropId: String,
)

data class PickupResponse(
    val kind: CommandAckKind,
    val dropId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, dropId: String) =
            PickupResponse(CommandAckKind.QUEUED, dropId, commandId, appliesAtTick)
    }
}
