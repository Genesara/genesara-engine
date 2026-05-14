package dev.gvart.genesara.api.internal.mcp.tools.craft

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CraftResponse(
    val kind: CommandAckKind,
    val recipeId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, recipeId: String) =
            CraftResponse(CommandAckKind.QUEUED, recipeId, commandId, appliesAtTick)

        fun rejected(recipeId: String, reason: String, detail: String) =
            CraftResponse(CommandAckKind.REJECTED, recipeId, reason = reason, detail = detail)
    }
}
