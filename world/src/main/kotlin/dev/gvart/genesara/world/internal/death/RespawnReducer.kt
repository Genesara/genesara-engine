package dev.gvart.genesara.world.internal.death

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.Respawn]. Materializes a dead agent at their
 * resolved safe node and restores their body to full pools.
 *
 * **"Dead" means body at HP=0 AND not currently in the world.** Both
 * conditions hold after the post-passives death sweep removes the agent from
 * `state.positions`. An agent calling `respawn` while alive (or never having
 * died) hits [WorldRejection.NotDead].
 *
 * Resolution chain (delegated to [SafeNodeResolver]):
 *   1. Set checkpoint via `set_safe_node`.
 *   2. Race-keyed starter node.
 *   3. Random spawnable node.
 *
 * **Stale-checkpoint self-healing.** If the resolver returns a checkpoint node
 * id that's missing from `state.nodes` (admin deleted the node, or DB / state
 * cache lag), the reducer clears the agent's safe-node row and re-resolves
 * once. Without that retry the agent would be stuck — they can't respawn,
 * and they can't `set_safe_node` while dead.
 *
 * The body resets to [AgentBody.fromProfile] — full HP/Stamina/Mana/gauges.
 * The death-system spec keeps gear equipped and the inventory intact across
 * death (item drop on death depends on combat + a kill counter, both Phase 2);
 * only stats / position reset.
 *
 * **Rejection priority:**
 * `UnknownProfile` (state corruption: agent has a body but no profile) →
 * `NotDead` (caller isn't actually dead) → `NoSpawnableNode` (resolver
 * returned nothing → misconfigured world).
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
    // True dead state: zero HP, body exists, agent is unpositioned. An agent
    // who's at HP=0 but still positioned shouldn't reach here (the death
    // sweep removes them on the same tick HP hits zero); we still guard.
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

/**
 * Resolve the landing node, falling through past a stale checkpoint when the
 * resolver returns a node id that's missing from `state.nodes`. The first
 * candidate is whatever the resolver returns; if it's a stale checkpoint, we
 * clear the gateway row and re-resolve once (the second pass falls through
 * to starter / random because the gateway now misses on `find`).
 *
 * Returns null only when no spawnable site exists at all.
 */
private fun resolveLanding(
    agentId: dev.gvart.genesara.player.AgentId,
    state: WorldState,
    safeNodes: AgentSafeNodeGateway,
    resolver: SafeNodeResolver,
): SafeNodeResolution? {
    val first = resolver.resolveFor(agentId) ?: return null
    if (first.nodeId in state.nodes) return first

    if (first.fromCheckpoint) {
        // Wipe the stale checkpoint so future respawn calls don't loop on it,
        // then re-resolve. The second pass skips checkpoint (gateway is empty
        // for this agent now) and falls into starter / random.
        safeNodes.clear(agentId)
        val second = resolver.resolveFor(agentId) ?: return null
        return second.takeIf { it.nodeId in state.nodes }
    }
    // Resolver returned a non-checkpoint stale node — that's odd (the
    // starter / random fallbacks should be live), but bail to NoSpawnableNode
    // rather than loop.
    return null
}
