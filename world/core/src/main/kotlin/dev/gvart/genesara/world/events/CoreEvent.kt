package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import dev.gvart.genesara.world.WorldRejection
import java.util.UUID

sealed interface CoreEvent : WorldEvent {

    data class AgentSpawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent

    data class AgentMoved(
        val agent: AgentId,
        val from: NodeId,
        val to: NodeId,
        /** Stamina actually charged for this step after terrain, road, and speed-scaling adjustments. */
        val staminaSpent: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent

    data class AgentDespawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent

    /** Fired when an agent successfully binds their current node as their safe node. */
    data class SafeNodeSet(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent

    /**
     * Emitted by the [dev.gvart.genesara.world.commands.CoreCommand.Say] reducer with
     * the deterministic set of [listeners] resolved at the reducer's tick (every agent
     * within [mode]'s hop radius of [at], including the speaker for self-hearing). The
     * dispatcher fans this single event out by iterating [listeners] and writing one
     * `agent.spoke` envelope per listener — same shape as [BodyEvent.PassivesApplied].
     */
    data class AgentSpoke(
        val speaker: AgentId,
        val at: NodeId,
        val message: String,
        val mode: SpeechMode,
        val channel: SayChannel,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent

    /**
     * Reducer rejected the queued command at apply time. Surfaces the rejection on
     * the agent's event stream so they can react without polling. [kind] is the
     * rejection's class simple name (e.g. `"NotEnoughStamina"`, `"RecipeRequiresStation"`)
     * — agents branch on it. [rejection] carries the structured fields; Jackson
     * serializes the concrete data-class members directly.
     */
    data class CommandRejected(
        val agent: AgentId,
        val kind: String,
        val rejection: WorldRejection,
        override val tick: Long,
        val causedBy: UUID,
    ) : CoreEvent
}
