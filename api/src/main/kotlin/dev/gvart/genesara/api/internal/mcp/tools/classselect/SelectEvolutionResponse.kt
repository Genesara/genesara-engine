package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.player.AgentClass

/**
 * Response shape for `select_evolution`.
 *
 * - `kind = "ok"`: the evolution is now permanently set on the agent;
 *   `class_id` is the chosen evolution class, the pending offer columns are
 *   cleared, and `from` carries the previous (base) class id.
 * - `kind = "rejected"`: the choice was refused; `reason` carries the cause.
 *   Possible reasons: `no_pending_offer`, `not_offered`, `no_class_assigned`,
 *   `already_evolved`, `unknown_agent`. (An unparseable class id is rejected
 *   by the MCP framework before reaching the tool, the same way `EquipSlot`
 *   is.)
 */
data class SelectEvolutionResponse(
    val kind: String,
    val classId: AgentClass,
    val from: AgentClass? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(from: AgentClass, to: AgentClass) =
            SelectEvolutionResponse(kind = "ok", classId = to, from = from)

        fun rejected(classId: AgentClass, reason: String, detail: String? = null) =
            SelectEvolutionResponse(kind = "rejected", classId = classId, reason = reason, detail = detail)
    }
}
