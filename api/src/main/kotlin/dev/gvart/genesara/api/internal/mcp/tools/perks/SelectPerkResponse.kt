package dev.gvart.genesara.api.internal.mcp.tools.perks

/**
 * Response shape for `select_perk`.
 *
 * - `kind = "ok"`: the perk is now permanently chosen for (skill, milestone).
 * - `kind = "rejected"`: the choice was refused; `reason` carries the cause. Possible
 *   reasons: `unknown_perk`, `perk_mismatch`, `milestone_not_reached`, `already_chosen`.
 */
data class SelectPerkResponse(
    val kind: String,
    val skillId: String,
    val milestone: Int,
    val perkId: String,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(skillId: String, milestone: Int, perkId: String) =
            SelectPerkResponse("ok", skillId, milestone, perkId)

        fun rejected(skillId: String, milestone: Int, perkId: String, reason: String, detail: String? = null) =
            SelectPerkResponse("rejected", skillId, milestone, perkId, reason, detail)
    }
}
