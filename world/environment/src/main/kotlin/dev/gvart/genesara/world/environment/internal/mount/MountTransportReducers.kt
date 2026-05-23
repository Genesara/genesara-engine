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

/**
 * `mount(mount:<uuid>)` — agent climbs onto an idle mount they're same-node
 * with. Open riding: any agent can mount any idle mount they meet (no lock),
 * regardless of ownership. Owner gets a `TransportMounted` event so they
 * learn when someone else hops on their horse.
 */
fun reduceMountTransport(
    environment: EnvironmentSlice,
    core: CoreReadView,
    command: EnvironmentCommand.MountTransport,
    mounts: MountInstanceStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val agentAt = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val target = ensureNotNull(mounts.findById(command.mount)) {
        WorldRejection.UnknownMount(command.agent, command.mount)
    }
    ensure(!target.isDead) { WorldRejection.MountAlreadyDead(command.agent, command.mount) }
    ensure(target.nodeId == agentAt) {
        WorldRejection.MountNotAtSameNode(command.agent, command.mount, agentAt, target.nodeId)
    }
    val currentRide = mounts.findByRider(command.agent)
    if (currentRide != null) {
        raise(WorldRejection.AlreadyMounted(command.agent, currentRide.id))
    }
    val existingRider = target.mountedByAgentId
    if (existingRider != null) {
        raise(WorldRejection.MountAlreadyMounted(command.agent, command.mount, existingRider))
    }

    if (!mounts.update(target.copy(mountedByAgentId = command.agent))) {
        raise(WorldRejection.UnknownMount(command.agent, command.mount))
    }

    val events: List<WorldEvent> = listOf(
        EnvironmentEvent.TransportMounted(
            agent = command.agent,
            mount = target.id,
            mountType = target.type,
            owner = target.ownerAgentId,
            at = target.nodeId,
            tick = tick,
            causedBy = command.commandId,
        ),
    )
    ReducerOutput(sliceDelta = environment, effects = emptyList(), events = events)
}

/**
 * `dismount()` — clears the rider link on whatever mount the agent is
 * currently on (or rejects [WorldRejection.NotMounted]).
 */
fun reduceDismountTransport(
    environment: EnvironmentSlice,
    core: CoreReadView,
    command: EnvironmentCommand.DismountTransport,
    mounts: MountInstanceStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val agentAt = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val mount = ensureNotNull(mounts.findByRider(command.agent)) {
        WorldRejection.NotMounted(command.agent)
    }
    // Race-deleted mount = rider already effectively dismounted.
    if (!mounts.update(mount.copy(mountedByAgentId = null))) {
        return@either ReducerOutput(sliceDelta = environment, effects = emptyList(), events = emptyList())
    }
    val events: List<WorldEvent> = listOf(
        EnvironmentEvent.TransportDismounted(
            agent = command.agent,
            mount = mount.id,
            mountType = mount.type,
            at = agentAt,
            tick = tick,
            causedBy = command.commandId,
        ),
    )
    ReducerOutput(sliceDelta = environment, effects = emptyList(), events = events)
}
