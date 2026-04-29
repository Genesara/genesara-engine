package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
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
}
