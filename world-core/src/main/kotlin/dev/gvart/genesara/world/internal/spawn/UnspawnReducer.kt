package dev.gvart.genesara.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice

internal fun reduceUnspawn(
    core: CoreSlice,
    command: CoreCommand.UnspawnAgent,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val from = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val nextCore = core.copy(positions = core.positions - command.agent)
    val event = CoreEvent.AgentDespawned(command.agent, from, tick, causedBy = command.commandId)
    ReducerOutput(sliceDelta = nextCore, events = listOf(event))
}
