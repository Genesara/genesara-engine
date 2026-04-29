package dev.gvart.genesara.api.internal.mcp.tools.skills

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Return the agent's complete skill snapshot: every catalog skill, current XP, derived level, slot index (null if unslotted), and recommend count.")
class GetSkillsRequest

data class GetSkillsResponse(
    val slotCount: Int,
    val slotsFilled: Int,
    val skills: List<SkillView>,
)

data class SkillView(
    val id: String,
    val displayName: String,
    val category: String,
    val xp: Int,
    val level: Int,
    /** Slot index (0-based) the skill is permanently placed in, or null if unslotted. */
    val slotIndex: Int?,
    /** Number of times this skill has been recommended via `SkillRecommended`. 0..3. */
    val recommendCount: Int,
)
