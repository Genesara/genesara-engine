package dev.gvart.genesara.world.internal.death

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.Respawn]. Materializes a dead agent at their resolved safe
 * node and restores their body to full pools.
 *
 * "Dead" means body at `HP == 0` AND not currently positioned — both hold after the
 * post-passives death sweep removes the agent from `state.positions`. An agent calling
 * `respawn` while alive (or never having died) hits [WorldRejection.NotDead].
 *
 * Resolution order (delegated to [SafeNodeResolver]): checkpoint → race-keyed starter →
 * random spawnable. Stale-checkpoint self-healing: if the resolver returns a checkpoint
 * node that's missing from `state.nodes`, the safe-node row is cleared and resolution
 * runs once more (skipping checkpoint, falling through to starter / random) — without
 * that retry the agent would be stuck because they can't `set_safe_node` while dead.
 *
 * Body resets to [AgentBody.fromProfile] (full HP/Stamina/Mana/gauges). Gear and
 * inventory survive death; item drop on death is a Phase-2 combat feature.
 *
 * Rejection priority: `UnknownProfile` (state corruption) → `NotDead` →
 * `NoSpawnableNode` (resolver returned nothing — misconfigured world).
 */
internal fun reduceRespawn(
    state: WorldState,
    command: WorldCommand.Respawn,
    profiles: AgentProfileLookup,
    safeNodes: AgentSafeNodeGateway,
    resolver: SafeNodeResolver,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val profile = ensureNotNull(profiles.find(command.agent)) {
        WorldRejection.UnknownProfile(command.agent)
    }

    val body = state.bodyOf(command.agent)
    val isPositioned = command.agent in state.positions
    ensure(body != null && body.hp == 0 && !isPositioned) {
        WorldRejection.NotDead(command.agent)
    }

    val resolution = resolveLanding(command.agent, state, safeNodes, resolver)
    ensureNotNull(resolution) { WorldRejection.NoSpawnableNode(command.agent) }

    val freshBody = AgentBody.fromProfile(profile)
    val next = state
        .copy(positions = state.positions + (command.agent to resolution.nodeId))
        .updateBody(command.agent, freshBody)
    val event = WorldEvent.AgentRespawned(
        agent = command.agent,
        at = resolution.nodeId,
        fromCheckpoint = resolution.fromCheckpoint,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}

private fun resolveLanding(
    agentId: AgentId,
    state: WorldState,
    safeNodes: AgentSafeNodeGateway,
    resolver: SafeNodeResolver,
): SafeNodeResolution? {
    val first = resolver.resolveFor(agentId) ?: return null
    if (first.nodeId in state.nodes) return first
    if (first.fromCheckpoint) return clearStaleCheckpointAndRetry(agentId, state, safeNodes, resolver)
    return null
}

private fun clearStaleCheckpointAndRetry(
    agentId: AgentId,
    state: WorldState,
    safeNodes: AgentSafeNodeGateway,
    resolver: SafeNodeResolver,
): SafeNodeResolution? {
    safeNodes.clear(agentId)
    val second = resolver.resolveFor(agentId) ?: return null
    return second.takeIf { it.nodeId in state.nodes }
}
