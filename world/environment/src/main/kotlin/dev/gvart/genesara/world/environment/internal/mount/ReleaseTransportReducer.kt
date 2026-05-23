package dev.gvart.genesara.world.environment.internal.mount

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

fun reduceReleaseTransport(
    environment: EnvironmentSlice,
    core: CoreReadView,
    command: EnvironmentCommand.ReleaseTransport,
    mounts: MountInstanceStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val agentNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val mount = ensureNotNull(mounts.findById(command.mount)) {
        WorldRejection.UnknownMount(command.agent, command.mount)
    }
    ensure(mount.ownerAgentId == command.agent) {
        WorldRejection.NotYourMount(command.agent, command.mount)
    }
    ensure(mount.nodeId == agentNode) {
        WorldRejection.MountNotAtSameNode(command.agent, command.mount, agentNode, mount.nodeId)
    }

    mounts.update(mount.copy(ownerAgentId = null))

    val events = listOf<WorldEvent>(
        EnvironmentEvent.TransportReleased(
            agent = command.agent,
            mount = mount.id,
            mountType = mount.type,
            at = mount.nodeId,
            tick = tick,
            causedBy = command.commandId,
        ),
    )
    ReducerOutput(sliceDelta = environment, events = events)
}
