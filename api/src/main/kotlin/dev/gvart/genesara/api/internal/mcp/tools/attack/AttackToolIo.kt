package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class AttackResponse(
    val kind: CommandAckKind,
    val targetAgentId: UUID,
    val commandId: UUID,
    val appliesAtTick: Long,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, targetAgentId: UUID) =
            AttackResponse(CommandAckKind.QUEUED, targetAgentId, commandId, appliesAtTick)
    }
}
