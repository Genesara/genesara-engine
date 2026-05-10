package dev.gvart.genesara.api.internal.mcp.tools.classselect

/**
 * Response shape for `select_class`.
 *
 * - `kind = "ok"`: the class is now permanently set on the agent and the
 *   pending offer columns are cleared.
 * - `kind = "rejected"`: the choice was refused; `reason` carries the cause.
 *   Possible reasons: `unknown_class`, `no_pending_offer`, `not_offered`,
 *   `already_classed`, `unknown_agent`.
 */
data class SelectClassResponse(
    val kind: String,
    val classId: String,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(classId: String) = SelectClassResponse("ok", classId)

        fun rejected(classId: String, reason: String, detail: String? = null) =
            SelectClassResponse("rejected", classId, reason, detail)
    }
}
