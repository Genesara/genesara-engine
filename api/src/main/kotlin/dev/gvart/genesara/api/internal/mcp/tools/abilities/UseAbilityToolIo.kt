package dev.gvart.genesara.api.internal.mcp.tools.abilities

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import java.util.UUID

data class UseAbilityResponse(
    val kind: CommandAckKind,
    val abilityId: String,
    /** Wire-prefixed `agent:<uuid>` echoed back; null when the ability had no target (SELF / AREA_SELF_NODE). */
    val targetAgentId: String?,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, abilityId: String, targetAgentId: String?) =
            UseAbilityResponse(
                kind = CommandAckKind.QUEUED,
                abilityId = abilityId,
                targetAgentId = targetAgentId,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(abilityId: String, targetAgentId: String?, reason: String, detail: String) =
            UseAbilityResponse(
                kind = CommandAckKind.REJECTED,
                abilityId = abilityId,
                targetAgentId = targetAgentId,
                reason = reason,
                detail = detail,
            )
    }
}
