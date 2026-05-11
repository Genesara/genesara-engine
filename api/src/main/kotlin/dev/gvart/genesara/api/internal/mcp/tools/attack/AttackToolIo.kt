package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class AttackResponse(
    val kind: CommandAckKind,
    val targetAgentId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, targetAgentId: UUID) =
            AttackResponse(
                kind = CommandAckKind.QUEUED,
                targetAgentId = targetAgentId.toString(),
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(targetAgentId: String, reason: String, detail: String) =
            AttackResponse(
                kind = CommandAckKind.REJECTED,
                targetAgentId = targetAgentId,
                reason = reason,
                detail = detail,
            )
    }
}
