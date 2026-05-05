package dev.gvart.genesara.api.internal.mcp.tools.consume

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

@JsonClassDescription("Consume one unit of a held item to refill a survival gauge. The item must be in the agent's inventory and have a consumable effect.")
data class ConsumeRequest(
    @JsonPropertyDescription("Item id to consume (e.g. BERRY, HERB).")
    val itemId: String,
)

data class ConsumeResponse(
    val kind: CommandAckKind,
    val itemId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, itemId: String) =
            ConsumeResponse(CommandAckKind.QUEUED, itemId, commandId, appliesAtTick)
    }
}
