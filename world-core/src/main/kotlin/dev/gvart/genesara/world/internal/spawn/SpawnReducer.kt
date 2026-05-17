package dev.gvart.genesara.world.internal.spawn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView

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
    core: CoreSlice,
    bodyView: BodyReadView,
    command: CoreCommand.SpawnAgent,
    profiles: AgentProfileLookup,
    resolver: SpawnLocationResolver,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    ensure(command.agent !in core.positions) {
        WorldRejection.AlreadySpawned(command.agent)
    }
    val target = ensureNotNull(resolver.resolveFor(command.agent)) {
        WorldRejection.NoSpawnableNode(command.agent)
    }
    ensure(core.nodes.containsKey(target)) {
        WorldRejection.UnknownNode(target)
    }
    val profile = ensureNotNull(profiles.find(command.agent)) {
        WorldRejection.UnknownProfile(command.agent)
    }

    val body = bodyView.bodyOf(command.agent) ?: AgentBody.fromProfile(profile)
    val nextCore = core.copy(positions = core.positions + (command.agent to target))
    val effects = listOf<CrossZoneEffect>(CrossZoneEffect.UpdateBody(command.agent, body))
    val event = CoreEvent.AgentSpawned(command.agent, target, tick, causedBy = command.commandId)
    ReducerOutput(sliceDelta = nextCore, effects = effects, events = listOf(event))
}

/**
 * Transitional wrapper preserving the legacy `(state, …) → (state, events)` shape used by
 * the top-level dispatcher. Removed once the dispatcher is swept to the slice-shape call.
 */
internal fun reduceSpawn(
    state: WorldState,
    command: CoreCommand.SpawnAgent,
    profiles: AgentProfileLookup,
    resolver: SpawnLocationResolver,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reduceSpawn(state.core, state.body, command, profiles, resolver, tick)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
