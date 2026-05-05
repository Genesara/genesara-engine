package dev.gvart.genesara.api.internal.mcp.tools.attack

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.UUID

@JsonClassDescription(
    "Attempt one melee or ranged attack against another agent on the same node. " +
        "The reducer reads the attacker's MAIN_HAND weapon (or unarmed defaults), rolls dodge then crit, " +
        "applies damage, spends stamina, and grants weapon-skill XP. The resulting AgentAttacked " +
        "event arrives on both the attacker's and the target's event streams once the tick lands; if " +
        "the blow drops the target's HP to zero, an AgentDied event lands at the same tick. Rejected " +
        "if attacker and target are not on the same node, target is not in the world, target is " +
        "already dead, attacker is not in the world, or attacker has insufficient stamina.",
)
data class AttackRequest(
    @JsonPropertyDescription("Target agent id (UUID string).")
    val targetAgentId: String,
)

/**
 * Response shape for `attack`.
 *
 * - `kind = "queued"`: an AttackTarget command was queued; the result lands on `appliesAtTick`
 *   and shows up via the agent's event stream as an `agent.attacked` notification (and possibly
 *   `agent.died` if the blow killed).
 */
data class AttackResponse(
    val kind: String,
    val targetAgentId: String,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, targetAgentId: String) =
            AttackResponse("queued", targetAgentId, commandId, appliesAtTick)
    }
}
