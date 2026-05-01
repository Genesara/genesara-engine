package dev.gvart.genesara.world.internal.death

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.SetSafeNode]. Binds the agent's current node as
 * their checkpoint. The validation is "you must actually be at the node you're
 * marking" — agents can't pre-mark a remote location.
 *
 * Side-effects: writes through [AgentSafeNodeGateway] within the tick
 * transaction. The gateway is the source of truth for safe-node lookups —
 * `WorldState` doesn't carry a per-agent checkpoint field because the value is
 * sparse (most agents won't have one) and only read during respawn.
 *
 * **Rejection priority:** `NotInWorld` (no current position) is the only
 * rejection — there's nothing else to validate. The current node is read from
 * `state.positions[agent]` so the operation is implicitly "mark wherever I am
 * right now".
 */
internal fun reduceSetSafeNode(
    state: WorldState,
    command: WorldCommand.SetSafeNode,
    safeNodes: AgentSafeNodeGateway,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    // Sanity-check the node graph: a position pointing at an evicted node would
    // surface as state corruption (same shape as the gather reducer's check).
    ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    safeNodes.set(command.agent, nodeId, tick)
    val event = WorldEvent.SafeNodeSet(
        agent = command.agent,
        at = nodeId,
        tick = tick,
        causedBy = command.commandId,
    )
    state to event
}
