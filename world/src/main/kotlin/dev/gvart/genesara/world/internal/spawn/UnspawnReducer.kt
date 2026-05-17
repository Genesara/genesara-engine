package dev.gvart.genesara.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduceUnspawn(
    state: WorldState,
    command: WorldCommand.UnspawnAgent,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val from = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val next = state.copy(core = state.core.copy(positions = state.core.positions - command.agent))
    val event = WorldEvent.AgentDespawned(command.agent, from, tick, causedBy = command.commandId)
    next to listOf(event)
}
