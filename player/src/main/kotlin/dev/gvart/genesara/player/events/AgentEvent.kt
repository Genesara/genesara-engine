package dev.gvart.genesara.player.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import java.util.UUID

sealed interface AgentEvent {
    val tick: Long

    /**
     * Emitted when an agent's slotted skill XP crosses one of the milestone thresholds
     * (50, 100, 150) as a result of [causedBy]. Only fires for skills currently in a
     * slot — unslotted skills don't accrue XP. Perk-selection prompts will hang off
     * this event in a future slice.
     */
    data class SkillMilestoneReached(
        val agent: AgentId,
        val skill: SkillId,
        val milestone: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : AgentEvent

    /**
     * Emitted when an agent does an action mapped to a skill they haven't slotted
     * yet, suggesting they consider slotting it. Capped at 3 per (agent, skill) and
     * gated by a per-skill cooldown. Suppressed entirely once all slots are filled.
     */
    data class SkillRecommended(
        val agent: AgentId,
        val skill: SkillId,
        /** New recommend count after this firing — 1, 2, or 3. */
        val recommendCount: Int,
        /** How many slots remain open at the time of the event. */
        val slotsFree: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : AgentEvent
}