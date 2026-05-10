package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.player.AgentClass

/**
 * Response shape for `select_class`.
 *
 * - `kind = "ok"`: the class is now permanently set on the agent and the
 *   pending offer columns are cleared.
 * - `kind = "rejected"`: the choice was refused; `reason` carries the cause.
 *   Possible reasons: `no_pending_offer`, `not_offered`, `already_classed`,
 *   `unknown_agent`. (An unparseable class id is rejected by the MCP framework
 *   before reaching the tool, the same way `EquipSlot` is.)
 */
data class SelectClassResponse(
    val kind: String,
    val classId: AgentClass,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(classId: AgentClass) = SelectClassResponse("ok", classId)

        fun rejected(classId: AgentClass, reason: String, detail: String? = null) =
            SelectClassResponse("rejected", classId, reason, detail)
    }
}
