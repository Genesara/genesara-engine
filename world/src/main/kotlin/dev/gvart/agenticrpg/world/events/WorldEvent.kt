package dev.gvart.agenticrpg.world.events

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.BodyDelta
import dev.gvart.agenticrpg.world.NodeId
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
}
