package dev.gvart.genesara.api.internal.mcp.tools.safenode

import com.fasterxml.jackson.annotation.JsonClassDescription
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

@JsonClassDescription(
    "Bind your current node as your respawn checkpoint. On death you'll come back here. " +
        "Overwrites any prior checkpoint. Must be called while you're in the world (positioned).",
)
class SetSafeNodeRequest

data class SetSafeNodeResponse(
    val kind: CommandAckKind,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long) =
            SetSafeNodeResponse(CommandAckKind.QUEUED, commandId, appliesAtTick)
    }
}
