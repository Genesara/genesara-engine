package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class AttackNpcResponse(
    val kind: CommandAckKind,
    val npcId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, npcId: UUID) =
            AttackNpcResponse(
                kind = CommandAckKind.QUEUED,
                npcId = npcId.toString(),
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(npcId: String, reason: String, detail: String) =
            AttackNpcResponse(
                kind = CommandAckKind.REJECTED,
                npcId = npcId,
                reason = reason,
                detail = detail,
            )
    }
}
