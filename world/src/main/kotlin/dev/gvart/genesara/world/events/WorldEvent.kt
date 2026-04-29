package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import java.util.UUID

sealed interface WorldEvent {
    val tick: Long

    data class AgentSpawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class AgentMoved(
        val agent: AgentId,
        val from: NodeId,
        val to: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class AgentDespawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class PassivesApplied(
        val deltas: Map<AgentId, BodyDelta>,
        override val tick: Long,
    ) : WorldEvent

    data class ResourceGathered(
        val agent: AgentId,
        val at: NodeId,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class ItemConsumed(
        val agent: AgentId,
        val item: ItemId,
        val gauge: Gauge,
        val refilled: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Emitted when an agent successfully drank from a water-source terrain. [refilled] is
     * the actual gain after clamping to maxThirst, so an already-full agent who drinks
     * still emits the event with `refilled = 0`.
     */
    data class AgentDrank(
        val agent: AgentId,
        val at: NodeId,
        val refilled: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

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
    ) : WorldEvent

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
    ) : WorldEvent
}
