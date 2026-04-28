package dev.gvart.genesara.world.internal.consume

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Pure reducer for [WorldCommand.ConsumeItem]. Validates presence + ownership +
 * consumability, decrements the inventory by 1, refills the named gauge (clamped to
 * its max), and emits [WorldEvent.ItemConsumed].
 *
 * **Rejection priority:**
 * `NotInWorld` → `UnknownItem` → `ItemNotConsumable` → `ItemNotInInventory`. Catalog
 * checks before ownership so an agent learns about typos before about scarcity.
 *
 * Out of scope for this slice: partial-stack consumption (always 1 unit), poison /
 * negative-amount effects, "already at max" rejection (the refill is just clamped —
 * consuming a berry at full hunger is a small waste, not an error).
 */
internal fun reduceConsume(
    state: WorldState,
    command: WorldCommand.ConsumeItem,
    items: ItemLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    // Presence: agent must be in the world.
    ensure(command.agent in state.positions) { WorldRejection.NotInWorld(command.agent) }

    // Item must exist in the catalog.
    val item = ensureNotNull(items.byId(command.item)) { WorldRejection.UnknownItem(command.item) }

    // Item must be consumable.
    val effect = ensureNotNull(item.consumable) { WorldRejection.ItemNotConsumable(command.item) }

    // Agent must own at least one.
    val inventory = state.inventoryOf(command.agent)
    ensure(inventory.quantityOf(command.item) > 0) {
        WorldRejection.ItemNotInInventory(command.agent, command.item)
    }

    // Body presence implied; matches MovementReducer / GatherReducer treatment.
    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")

    val before = body.valueOf(effect.gauge)
    val nextBody = body.refill(effect.gauge, effect.amount)
    val refilled = nextBody.valueOf(effect.gauge) - before
    val nextInventory = inventory.remove(command.item, 1)
    val next = state
        .updateBody(command.agent, nextBody)
        .updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ItemConsumed(
        agent = command.agent,
        item = command.item,
        gauge = effect.gauge,
        refilled = refilled,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}
