package dev.gvart.genesara.api.internal.mcp.tools.skills

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription(
    "Permanently assign a skill to a slot. THIS IS IRREVERSIBLE — once a skill is in a slot, " +
        "it cannot be removed, swapped, or replaced. There is no unequip operation. " +
        "Verify the skill choice with `get_skills` before calling.",
)
data class EquipSkillRequest(
    @JsonPropertyDescription("Skill id from the catalog (e.g. FORAGING, MINING).")
    val skillId: String,
    @JsonPropertyDescription("Target slot index (0-based). Must be < slotCount from get_skills, and the slot must be empty.")
    val slotIndex: Int,
)

/**
 * Response shape for `equip_skill`.
 *
 * - `kind = "ok"`: the skill is now permanently in the slot.
 * - `kind = "rejected"`: the assignment was refused; `reason` carries the cause.
 *   Possible reasons: `slot_index_out_of_range`, `slot_occupied`,
 *   `skill_already_slotted`, `unknown_skill`.
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
