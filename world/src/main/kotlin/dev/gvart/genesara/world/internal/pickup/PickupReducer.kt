package dev.gvart.genesara.world.internal.pickup

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.Pickup]. Reads the candidate drop from
 * [GroundItemStore] before [GroundItemStore.take] so a stackable pickup that
 * would over-encumber the agent rejects without leaving the drop in limbo.
 *
 * The peek + take split has a small TOCTOU window: another agent's pickup may
 * win the atomic [GroundItemStore.take] between the carry-cap check and our
 * own take call. That second-place caller gets [WorldRejection.GroundItemNoLongerAvailable]
 * — the same rejection a stale dropId produces — which matches the
 * `harvest`-style "deposit gone" framing. Equipment drops skip the carry-cap
 * check (no body-grams accounting yet for unequipped instances; the equip
 * reducer enforces fit when the agent later slots them).
 */
internal fun reducePickup(
    state: WorldState,
    command: WorldCommand.Pickup,
    balance: BalanceLookup,
    items: ItemLookup,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    groundItems: GroundItemStore,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }

    val candidate: GroundItemView = groundItems.atNode(nodeId)
        .firstOrNull { it.drop.dropId == command.dropId }
        ?: raise(WorldRejection.GroundItemNoLongerAvailable(command.agent, command.dropId))

    if (candidate.drop is DroppedItemView.Stackable) {
        val stack = candidate.drop
        val itemDef = ensureNotNull(items.byId(stack.item)) {
            WorldRejection.UnknownItem(stack.item)
        }
        val currentInStack = state.inventoryOf(command.agent).quantityOf(stack.item)
        ensure(currentInStack + stack.quantity <= itemDef.maxStack) {
            WorldRejection.StackFull(
                agent = command.agent,
                item = stack.item,
                current = currentInStack,
                incoming = stack.quantity,
                maxStack = itemDef.maxStack,
            )
        }
        val agentRecord = agents.find(command.agent)
            ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
        val currentGrams = state.inventoryOf(command.agent).totalGrams(items) +
            equippedGrams(equipment.equippedFor(command.agent), items)
        val additionalGrams = stack.quantity * itemDef.weightPerUnit
        enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)
    }

    val taken = groundItems.take(nodeId, command.dropId)
        ?: raise(WorldRejection.GroundItemNoLongerAvailable(command.agent, command.dropId))

    val nextState = applyPickup(state, command.agent, taken.drop, equipment)
    val event = WorldEvent.ItemPickedUp(
        agent = command.agent,
        at = nodeId,
        drop = taken.drop,
        tick = tick,
        causedBy = command.commandId,
    )
    nextState to event
}

private fun applyPickup(
    state: WorldState,
    agent: AgentId,
    drop: DroppedItemView,
    equipment: EquipmentInstanceStore,
): WorldState = when (drop) {
    is DroppedItemView.Stackable -> state.updateInventory(
        agent,
        state.inventoryOf(agent).add(drop.item, drop.quantity),
    )
    is DroppedItemView.Equipment -> {
        equipment.insert(
            EquipmentInstance(
                instanceId = drop.instanceId,
                agentId = agent,
                itemId = drop.item,
                rarity = drop.rarity,
                durabilityCurrent = drop.durabilityCurrent,
                durabilityMax = drop.durabilityMax,
                creatorAgentId = drop.creatorAgentId?.let(::AgentId),
                createdAtTick = drop.createdAtTick,
                equippedInSlot = null,
            ),
        )
        state
    }
}
