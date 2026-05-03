package dev.gvart.genesara.world.internal.drink

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Pure reducer for [WorldCommand.Drink]. Validates presence + terrain + stamina, applies
 * the cost, refills THIRST (clamped to maxThirst), and emits [WorldEvent.AgentDrank].
 *
 * **Rejection priority (mirrors GatherReducer's contract):**
 * `NotInWorld` → `UnknownNode` (state corruption) → `NotAWaterSource` →
 * `NotEnoughStamina`. Terrain mismatch comes before stamina so a misplaced agent doesn't
 * burn stamina figuring out their location.
 *
 * Drinking at full thirst is **not** a rejection — the refill clamps to zero and the
 * event still fires. Same shape as `consume`: a wasted action, not an error.
 *
 * Out of scope for this slice: skill XP grant on drink, water-source depletion (rivers
 * never run dry in v1), drinking from inventory water items (uses `consume` instead —
 * see project memory `project_drinking_design.md`).
 */
internal fun reduceDrink(
    state: WorldState,
    command: WorldCommand.Drink,
    balance: BalanceLookup,
    buildings: BuildingsLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val node = ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val hasWell = buildings.activeStationsAt(nodeId, BuildingCategoryHint.UTILITY_WATER).isNotEmpty()
    ensure(hasWell || balance.isWaterSource(node.terrain)) {
        WorldRejection.NotAWaterSource(command.agent, nodeId)
    }

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.drinkStaminaCost()
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    val refillAmount = balance.drinkThirstRefill()
    val before = body.thirst
    val nextBody = body
        .spendStamina(cost)
        .refill(Gauge.THIRST, refillAmount)
    val refilled = nextBody.thirst - before
    val next = state.updateBody(command.agent, nextBody)
    val event = WorldEvent.AgentDrank(
        agent = command.agent,
        at = nodeId,
        refilled = refilled,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}
