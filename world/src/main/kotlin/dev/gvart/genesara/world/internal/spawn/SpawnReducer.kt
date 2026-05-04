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

/**
 * Spawns or resumes an agent. Despawn removes only the position — the body persists, so
 * character progress (HP, stamina, future XP/skills) carries across sessions. Falls
 * through to a fresh body from [AgentBody.fromProfile] only on the agent's first-ever
 * spawn. The destination node is decided by [SpawnLocationResolver] so the reducer
 * stays focused on body/position mutation.
 *
 * Rejection priority: `AlreadySpawned` → `NoSpawnableNode` → `UnknownNode` →
 * `UnknownProfile`. `UnknownNode` here is a defensive guard — the resolver reads from
 * the same backing store as `state.nodes`, so a returned-but-missing node is unreachable
 * in production today; we surface a rejection rather than self-heal because there is no
 * `safeNodes.clear`-like escape hatch (mirrors `RespawnReducer`'s checkpoint-stale
 * handling, with `agent_positions` integrity covered by FK constraints).
 */
internal fun reduceSpawn(
    state: WorldState,
    command: WorldCommand.SpawnAgent,
    profiles: AgentProfileLookup,
    resolver: SpawnLocationResolver,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    ensure(command.agent !in state.positions) {
        WorldRejection.AlreadySpawned(command.agent)
    }
    val target = ensureNotNull(resolver.resolveFor(command.agent)) {
        WorldRejection.NoSpawnableNode(command.agent)
    }
    ensure(state.nodes.containsKey(target)) {
        WorldRejection.UnknownNode(target)
    }
    val profile = ensureNotNull(profiles.find(command.agent)) {
        WorldRejection.UnknownProfile(command.agent)
    }

    val body = state.bodyOf(command.agent) ?: AgentBody.fromProfile(profile)
    val next = state
        .copy(positions = state.positions + (command.agent to target))
        .updateBody(command.agent, body)
    val event = WorldEvent.AgentSpawned(command.agent, target, tick, causedBy = command.commandId)
    next to event
}
