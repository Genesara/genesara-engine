package dev.gvart.genesara.api.internal.mcp.tools.attack

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Attempt one melee or ranged attack against another agent within the wielded weapon's range " +
        "(or unarmed reach when no weapon is equipped). The reducer reads the attacker's MAIN_HAND " +
        "weapon (or unarmed defaults), rolls dodge then crit, applies damage, spends stamina, and " +
        "grants weapon-skill XP. The resulting AgentAttacked event arrives on both the attacker's " +
        "and the target's event streams once the tick lands; if the blow drops the target's HP to " +
        "zero, an AgentDied event lands at the same tick. Rejected if the target is beyond the " +
        "weapon's range, not in the world, already dead, or if the attacker is not in the world or " +
        "has insufficient stamina.",
)
data class AttackRequest(
    @JsonPropertyDescription("Target agent UUID (read it from look_around / inspect).")
    val targetAgentId: UUID,
)

enum class AttackResponseKind { QUEUED }

data class AttackResponse(
    val kind: AttackResponseKind,
    val targetAgentId: UUID,
    val commandId: UUID,
    val appliesAtTick: Long,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, targetAgentId: UUID) =
            AttackResponse(AttackResponseKind.QUEUED, targetAgentId, commandId, appliesAtTick)
    }
}
