package dev.gvart.genesara.api.internal.mcp.tools.abilities

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class UseAbilityResponse(
    val kind: CommandAckKind,
    val abilityId: String,
    val targetAgentId: UUID?,
    val commandId: UUID,
    val appliesAtTick: Long,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, abilityId: String, targetAgentId: UUID?) =
            UseAbilityResponse(CommandAckKind.QUEUED, abilityId, targetAgentId, commandId, appliesAtTick)
    }
}
