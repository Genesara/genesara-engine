package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class AttackResponse(
    val kind: CommandAckKind,
    /** On QUEUED: wire-prefixed `agent:<uuid>` or `npc:<uuid>` (re-encoded from the parsed id). On REJECTED: the raw input echoed verbatim so the caller can see what they sent. */
    val target: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, target: String) =
            AttackResponse(
                kind = CommandAckKind.QUEUED,
                target = target,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(target: String, reason: String, detail: String) =
            AttackResponse(
                kind = CommandAckKind.REJECTED,
                target = target,
                reason = reason,
                detail = detail,
            )
    }
}
