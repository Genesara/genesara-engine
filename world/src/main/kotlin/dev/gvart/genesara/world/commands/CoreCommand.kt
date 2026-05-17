package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = CoreCommand.SpawnAgent::class, name = "spawn"),
    JsonSubTypes.Type(value = CoreCommand.MoveAgent::class, name = "move"),
    JsonSubTypes.Type(value = CoreCommand.UnspawnAgent::class, name = "unspawn"),
    JsonSubTypes.Type(value = CoreCommand.Say::class, name = "say"),
    JsonSubTypes.Type(value = CoreCommand.SetSafeNode::class, name = "setSafeNode"),
)
sealed interface CoreCommand : WorldCommand {

    /**
     * Enter the world. The reducer resolves the destination via the canonical
     * fallback chain (resume last-known position → race-keyed starter node →
     * random spawnable node); the resolved node is reported on the resulting
     * [dev.gvart.genesara.world.events.CoreEvent.AgentSpawned].
     */
    data class SpawnAgent(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CoreCommand

    data class MoveAgent(
        override val agent: AgentId,
        val to: NodeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CoreCommand

    data class UnspawnAgent(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CoreCommand

    /**
     * Bind the agent's current node as their respawn checkpoint. The reducer
     * validates the agent is positioned at the marker node — agents can't
     * pre-mark a remote location.
     */
    data class SetSafeNode(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CoreCommand

    /**
     * Speak [message] aloud. The reducer resolves listeners by BFS over node adjacency
     * out to [SpeechMode]'s configured radius and emits a single
     * [dev.gvart.genesara.world.events.CoreEvent.AgentSpoke] carrying the listener set;
     * the speaker is always included (self-hearing). v1 supports only [SayChannel.LOCAL]
     * — clan/trade channels land with Phase 3.
     */
    data class Say(
        override val agent: AgentId,
        val message: String,
        val mode: SpeechMode,
        val channel: SayChannel,
        override val commandId: UUID = UUID.randomUUID(),
    ) : CoreCommand
}
