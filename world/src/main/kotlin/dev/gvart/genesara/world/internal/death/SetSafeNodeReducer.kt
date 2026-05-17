package dev.gvart.genesara.world.internal.death

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice

/**
 * Reducer for [CoreCommand.SetSafeNode]. Binds the agent's current node as
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
    core: CoreSlice,
    command: CoreCommand.SetSafeNode,
    safeNodes: AgentSafeNodeGateway,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    // State-corruption guard mirroring the harvest reducer — a position pointing at
    // an evicted node surfaces here rather than crashing later writes.
    ensureNotNull(core.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    safeNodes.set(command.agent, nodeId, tick)
    val event = CoreEvent.SafeNodeSet(
        agent = command.agent,
        at = nodeId,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = core, events = listOf(event))
}

/**
 * Transitional wrapper preserving the legacy `(state, …) → (state, events)` shape used by
 * the top-level dispatcher.
 */
internal fun reduceSetSafeNode(
    state: WorldState,
    command: CoreCommand.SetSafeNode,
    safeNodes: AgentSafeNodeGateway,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reduceSetSafeNode(state.core, command, safeNodes, tick)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
