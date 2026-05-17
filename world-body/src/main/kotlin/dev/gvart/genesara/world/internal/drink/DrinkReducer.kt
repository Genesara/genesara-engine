package dev.gvart.genesara.world.internal.drink

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * Pure reducer for [BodyCommand.Drink]. Validates presence + terrain + stamina, applies
 * the cost, refills THIRST (clamped to maxThirst), and emits [BodyEvent.AgentDrank].
 *
 * **Rejection priority (mirrors HarvestReducer's contract):**
 * `NotInWorld` → `UnknownNode` (state corruption) → `NotAWaterSource` →
 * `NotEnoughStamina`. Terrain mismatch comes before stamina so a misplaced agent doesn't
 * burn stamina figuring out their location.
 */
internal fun reduceDrink(
    body: BodySlice,
    core: CoreReadView,
    command: BodyCommand.Drink,
    balance: BalanceLookup,
    buildings: BuildingsLookup,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val node = ensureNotNull(core.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val hasWell = buildings.activeStationsAt(nodeId, BuildingCategoryHint.UTILITY_WATER).isNotEmpty()
    ensure(hasWell || balance.isWaterSource(node.terrain)) {
        WorldRejection.NotAWaterSource(command.agent, nodeId)
    }

    val currentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.drinkStaminaCost()
    ensure(currentBody.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, currentBody.stamina)
    }

    val refillAmount = balance.drinkThirstRefill()
    val before = currentBody.thirst
    val nextBody = currentBody
        .spendStamina(cost)
        .refill(Gauge.THIRST, refillAmount)
    val refilled = nextBody.thirst - before
    val nextSlice = body.copy(bodies = body.bodies + (command.agent to nextBody))
    val event = BodyEvent.AgentDrank(
        agent = command.agent,
        at = nodeId,
        refilled = refilled,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextSlice, events = listOf(event))
}
