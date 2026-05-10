package dev.gvart.genesara.player.events

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import java.util.UUID

sealed interface AgentEvent {
    val tick: Long

    /**
     * Emitted when an agent's slotted skill XP crosses one of the milestone thresholds
     * (50, 100, 150) as a result of [causedBy]. Only fires for skills currently in a
     * slot — unslotted skills don't accrue XP. When the catalog defines perks at the
     * crossed milestone, a [PerkChoiceOffered] event is published immediately after.
     */
    data class SkillMilestoneReached(
        val agent: AgentId,
        val skill: SkillId,
        val milestone: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : AgentEvent

    /**
     * Emitted right after [SkillMilestoneReached] when the catalog defines perks at
     * that milestone. Carries the binary fork the agent must commit via `select_perk`.
     * The offer has no expiry — the agent can defer the pick indefinitely; pending
     * offers are also surfaced in the `get_status` projection so a missed event can
     * be recovered on read.
     */
    data class PerkChoiceOffered(
        val agent: AgentId,
        val skill: SkillId,
        val milestone: Int,
        val options: List<PerkId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : AgentEvent

    /**
     * Emitted when an agent commits to a perk via `select_perk`. Choice is forever
     * (no re-roll) — mirrors the slot-permanence rule for skills.
     */
    data class PerkChosen(
        val agent: AgentId,
        val skill: SkillId,
        val milestone: Int,
        val perk: PerkId,
        override val tick: Long,
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

    /**
     * Emitted when an [allocate_points] call pushes [attribute] across one of the
     * milestone thresholds (50, 100, 200). Perk-catalog wiring lands later — this
     * event only marks the crossing.
     */
    data class AttributeMilestoneReached(
        val agent: AgentId,
        val attribute: Attribute,
        val milestone: Int,
        override val tick: Long,
    ) : AgentEvent

    /**
     * Emitted once when an agent reaches level 10 with no class. The two
     * [candidates] are the top-2 classes scored against the agent's behavior
     * fingerprint; the agent commits one via `select_class`. Agent stays at
     * level 10 until the pick is made (further character XP is capped at the
     * level-10 boundary).
     *
     * The [candidates] list mirrors the scorer's ranking: index 0 is the
     * strongest fingerprint match, index 1 the runner-up. Either is a legal
     * pick — the order is informational, not a forced default.
     */
    data class ClassChoiceOffered(
        val agent: AgentId,
        val candidates: List<AgentClass>,
        override val tick: Long,
    ) : AgentEvent

    /**
     * Emitted when an agent commits to a class via `select_class`. The class
     * choice is forever — there is no respec — mirroring the no-respec rule
     * for skill perks.
     */
    data class ClassChosen(
        val agent: AgentId,
        val classId: AgentClass,
        override val tick: Long,
    ) : AgentEvent
}
