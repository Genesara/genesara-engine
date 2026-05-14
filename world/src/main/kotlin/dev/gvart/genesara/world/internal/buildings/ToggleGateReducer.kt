package dev.gvart.genesara.world.internal.buildings

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.ToggleGate]. Validates the agent is co-located
 * with the gate, the gate is ACTIVE, and the agent holds a matching key.
 * Toggles state and emits [WorldEvent.GateToggled] carrying the post-flip
 * state.
 *
 * No stamina cost: a key-holding agent at the gate's node is presumed to
 * have walked up to it; the toggle itself is a near-zero action.
 */
internal fun reduceToggleGate(
    state: WorldState,
    command: WorldCommand.ToggleGate,
    buildings: BuildingsStore,
    gateStates: BuildingGateStateStore,
    keys: AgentItemInstancesStore,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val agentNode = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val gate = ensureNotNull(buildings.findById(command.gateId)) {
        WorldRejection.GateNotFound(command.agent, command.gateId)
    }
    ensure(gate.type == BuildingType.GATE) {
        WorldRejection.GateNotFound(command.agent, command.gateId)
    }
    ensure(gate.status == BuildingStatus.ACTIVE) {
        WorldRejection.BuildingNotActive(command.gateId, gate.status)
    }
    ensure(gate.nodeId == agentNode) {
        WorldRejection.NotOnBuildingNode(command.agent, command.gateId)
    }
    ensure(keys.agentHoldsKeyFor(command.agent, command.gateId)) {
        WorldRejection.MissingGateKey(command.agent, command.gateId)
    }
    val newOpen = ensureNotNull(gateStates.toggle(command.gateId)) {
        // Defensive: gate row exists but its state row is missing. The
        // build-complete side-effect inserts both atomically, so this is a
        // schema-invariant break, not a user-facing case. Collapse to
        // GateNotFound so we don't leak the storage layout.
        WorldRejection.GateNotFound(command.agent, command.gateId)
    }
    val event = WorldEvent.GateToggled(
        agent = command.agent,
        gateId = command.gateId,
        at = gate.nodeId,
        isOpen = newOpen,
        tick = tick,
        causedBy = command.commandId,
    )
    state to listOf(event)
}
