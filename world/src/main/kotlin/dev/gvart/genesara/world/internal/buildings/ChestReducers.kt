package dev.gvart.genesara.world.internal.buildings

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID

internal fun reduceDeposit(
    state: WorldState,
    command: WorldCommand.DepositToChest,
    items: ItemLookup,
    catalog: BuildingsCatalog,
    buildings: BuildingsStore,
    chestContents: ChestContentsStore,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    ensure(command.quantity > 0) { WorldRejection.NonPositiveQuantity(command.agent, command.quantity) }
    val agentNode = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val chest = resolveOwnedActiveChestAtAgentNode(command.agent, command.chestId, agentNode, buildings)
    val def = catalog.def(BuildingType.STORAGE_CHEST)

    val inventory = state.inventoryOf(command.agent)
    val have = inventory.quantityOf(command.item)
    ensure(have >= command.quantity) {
        WorldRejection.ItemNotInInventory(command.agent, command.item)
    }

    val itemDef = ensureNotNull(items.byId(command.item)) { WorldRejection.UnknownItem(command.item) }
    val capacity = def.chestCapacityGrams
        ?: error("STORAGE_CHEST def is missing chestCapacityGrams (catalog validator should have caught this)")
    val currentGrams = chestContents.contentsOf(chest.instanceId).entries.sumOf { (id, qty) ->
        (items.byId(id)?.weightPerUnit ?: 0) * qty
    }
    val attemptedGrams = currentGrams + itemDef.weightPerUnit * command.quantity
    ensure(attemptedGrams <= capacity) {
        WorldRejection.ChestCapacityExceeded(chest.instanceId, attemptedGrams, capacity)
    }

    chestContents.add(chest.instanceId, command.item, command.quantity)
    val nextInventory = inventory.remove(command.item, command.quantity)
    val next = state.updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ItemDeposited(
        agent = command.agent,
        chest = chest.instanceId,
        item = command.item,
        quantity = command.quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}

internal fun reduceWithdraw(
    state: WorldState,
    command: WorldCommand.WithdrawFromChest,
    buildings: BuildingsStore,
    chestContents: ChestContentsStore,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    ensure(command.quantity > 0) { WorldRejection.NonPositiveQuantity(command.agent, command.quantity) }
    val agentNode = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val chest = resolveOwnedActiveChestAtAgentNode(command.agent, command.chestId, agentNode, buildings)

    val available = chestContents.quantityOf(chest.instanceId, command.item)
    ensure(available >= command.quantity) {
        WorldRejection.ChestDoesNotContain(chest.instanceId, command.item, command.quantity, available)
    }

    // Within a single tick the queue serializes commands per agent and we just read
    // `quantityOf >= command.quantity`, so a `false` here is a real invariant break.
    val removed = chestContents.remove(chest.instanceId, command.item, command.quantity)
    check(removed) { "chestContents.remove disagreed with quantityOf — store invariant violated for ${chest.instanceId}" }
    val nextInventory = state.inventoryOf(command.agent).add(command.item, command.quantity)
    val next = state.updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ItemWithdrawn(
        agent = command.agent,
        chest = chest.instanceId,
        item = command.item,
        quantity = command.quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}

private fun Raise<WorldRejection>.resolveOwnedActiveChestAtAgentNode(
    agent: AgentId,
    chestId: UUID,
    agentNode: NodeId,
    buildings: BuildingsStore,
): Building {
    val chest = ensureNotNull(buildings.findById(chestId)) { WorldRejection.BuildingNotFound(chestId) }
    // Privacy: surface a "wrong type" hit as BuildingNotFound so an agent cannot probe
    // what other building types live at an id they happened to learn.
    ensure(chest.type == BuildingType.STORAGE_CHEST) { WorldRejection.BuildingNotFound(chestId) }
    // Owner-before-location is intentional: ownership is the load-bearing access gate
    // (Phase 1 chests are personal); leaking ownership-by-id is acceptable because chest
    // ids aren't enumerable, but leaking a chest's location would not be.
    ensure(chest.builtByAgentId == agent) { WorldRejection.NotChestOwner(agent, chestId) }
    ensure(chest.nodeId == agentNode) { WorldRejection.NotOnBuildingNode(agent, chestId) }
    ensure(chest.status == BuildingStatus.ACTIVE) { WorldRejection.BuildingNotActive(chestId, chest.status) }
    return chest
}
