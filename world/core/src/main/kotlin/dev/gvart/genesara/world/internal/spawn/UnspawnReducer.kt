package dev.gvart.genesara.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice

fun reduceUnspawn(
    core: CoreSlice,
    command: CoreCommand.UnspawnAgent,
    tick: Long,
    mounts: MountInstanceStore = MountInstanceStore.NoOp,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val from = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    // Auto-dismount: a logging-out rider must release their mount so its
    // fatigue regenerates while they're away (mounted_by stays meaning
    // "actively-ridden-by-online-agent"). No event — the dismount is implicit
    // in the unspawn event.
    mounts.findByRider(command.agent)?.let { mount ->
        mounts.update(mount.copy(mountedByAgentId = null))
    }
    val nextCore = core.copy(positions = core.positions - command.agent)
    val event = CoreEvent.AgentDespawned(command.agent, from, tick, causedBy = command.commandId)
    ReducerOutput(sliceDelta = nextCore, events = listOf(event))
}
