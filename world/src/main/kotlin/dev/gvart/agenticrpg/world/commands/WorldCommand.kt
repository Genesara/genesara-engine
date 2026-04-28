package dev.gvart.agenticrpg.world.commands

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.NodeId
import java.util.UUID

sealed interface WorldCommand {
    val agent: AgentId
    val commandId: UUID

    data class SpawnAgent(
        override val agent: AgentId,
        val at: NodeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class MoveAgent(
        override val agent: AgentId,
        val to: NodeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class UnspawnAgent(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand
}
