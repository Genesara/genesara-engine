package dev.gvart.agenticrpg.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.agenticrpg.world.WorldRejection
import dev.gvart.agenticrpg.world.commands.WorldCommand
import dev.gvart.agenticrpg.world.events.WorldEvent
import dev.gvart.agenticrpg.world.internal.worldstate.WorldState

internal fun reduceUnspawn(
    state: WorldState,
    command: WorldCommand.UnspawnAgent,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val from = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.UnknownAgent(command.agent)
    }
    val next = state.copy(positions = state.positions - command.agent)
    val event = WorldEvent.AgentDespawned(command.agent, from, tick, causedBy = command.commandId)
    next to event
}
