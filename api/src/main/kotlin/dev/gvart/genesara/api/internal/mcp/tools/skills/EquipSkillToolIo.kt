package dev.gvart.genesara.api.internal.mcp.tools.skills

/**
 * Response shape for `equip_skill`.
 *
 * - `kind = "ok"`: the skill is now permanently in the slot.
 * - `kind = "rejected"`: the assignment was refused; `reason` carries the cause.
 *   Possible reasons: `slot_index_out_of_range`, `slot_occupied`,
 *   `skill_already_slotted`, `unknown_skill`, `unknown_agent`,
 *   `skill_not_discovered` (also surfaces when a class-locked skill is
 *   slotted by an agent of the wrong class — the catalog stays hidden).
 */
data class EquipSkillResponse(
    val kind: String,
    val skillId: String,
    val slotIndex: Int,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(skillId: String, slotIndex: Int) =
            EquipSkillResponse("ok", skillId, slotIndex)

        fun rejected(skillId: String, slotIndex: Int, reason: String, detail: String? = null) =
            EquipSkillResponse("rejected", skillId, slotIndex, reason, detail)
    }
}
