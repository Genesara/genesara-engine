package dev.gvart.genesara.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduceSpawn(
    state: WorldState,
    command: WorldCommand.SpawnAgent,
    profiles: AgentProfileLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    ensure(command.agent !in state.positions) {
        WorldRejection.AlreadySpawned(command.agent)
    }
    ensure(state.nodes.containsKey(command.at)) {
        WorldRejection.UnknownNode(command.at)
    }
    val profile = ensureNotNull(profiles.find(command.agent)) {
        WorldRejection.UnknownProfile(command.agent)
    }

    // Resume the persisted body if the agent has played before; only fresh-from-profile on
    // first-ever spawn. Despawn removes the position but keeps the body so character progress
    // (HP, stamina, future XP/skills) carries across sessions.
    val body = state.bodyOf(command.agent) ?: AgentBody.fromProfile(profile)
    val next = state
        .copy(positions = state.positions + (command.agent to command.at))
        .updateBody(command.agent, body)
    val event = WorldEvent.AgentSpawned(command.agent, command.at, tick, causedBy = command.commandId)
    next to event
}