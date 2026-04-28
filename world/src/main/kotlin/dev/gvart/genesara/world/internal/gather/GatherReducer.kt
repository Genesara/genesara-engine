package dev.gvart.genesara.world.internal.gather

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Pure reducer for [WorldCommand.GatherResource]. Validates presence + terrain + stamina,
 * applies the cost, increments the agent's inventory, and emits [WorldEvent.ResourceGathered].
 *
 * **Rejection priority (load-bearing for the agent-facing API contract):**
 * `NotInWorld` → `UnknownNode` (state corruption) → `UnknownItem` →
 * `ResourceNotAvailableHere` → `NotEnoughStamina`. The order is deliberate: cheap predicates
 * first, agent-correctable conditions before world-config issues, terrain mismatch before
 * stamina so a misplaced agent doesn't burn stamina figuring out their location.
 *
 * Out of scope for this slice: per-node depletion, skill XP grant, carry-weight cap, and
 * `Item.maxStack` enforcement. Each is left as a clearly-marked TODO so the next slice
 * that introduces it has an obvious insertion point.
 */
internal fun reduceGather(
    state: WorldState,
    command: WorldCommand.GatherResource,
    balance: BalanceLookup,
    items: ItemLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    // Presence: agent must be in the world.
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val node = ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    // Item must exist in the catalog.
    ensureNotNull(items.byId(command.item)) { WorldRejection.UnknownItem(command.item) }

    // Terrain must list the item among its gatherables.
    ensure(command.item in balance.gatherablesIn(node.terrain)) {
        WorldRejection.ResourceNotAvailableHere(command.agent, nodeId, command.item)
    }

    // Stamina cost. Presence implies a body (spawn always materialises one); a missing body
    // here is an internal invariant violation, not an agent-facing rejection — same shape
    // as `MovementReducer.reduceMove`.
    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.gatherStaminaCost(command.item)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    // TODO(skills): grant Gathering skill XP here when the skill schema lands.
    // TODO(depletion): decrement node resource availability when per-node tracking lands.
    // TODO(carry-weight): reject when total weight would exceed Strength × multiplier when
    //                     the equipment slice introduces the cap.
    // TODO(max-stack): reject (StackFull) when adding `quantity` would exceed
    //                  `items.byId(item)!!.maxStack`. Field exists today but is unenforced.
    val quantity = balance.gatherYield(command.item)
    val nextInventory = state.inventoryOf(command.agent).add(command.item, quantity)
    val next = state
        .updateBody(command.agent, body.spendStamina(cost))
        .updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ResourceGathered(
        agent = command.agent,
        at = nodeId,
        item = command.item,
        quantity = quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}
