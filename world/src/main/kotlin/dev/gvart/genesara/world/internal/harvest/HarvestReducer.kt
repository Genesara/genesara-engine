package dev.gvart.genesara.world.internal.harvest

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Reducer for [WorldCommand.Harvest].
 *
 * Mutates [NodeResourceStore.decrement] outside [WorldState] because per-node cells
 * are too large to load into the aggregate every tick. The caller (`WorldTickHandler`)
 * owns the surrounding transaction; if its tx rolls back, the decrement rolls back too.
 */
internal fun reduceHarvest(
    state: WorldState,
    command: WorldCommand.Harvest,
    balance: BalanceLookup,
    items: ItemLookup,
    resources: NodeResourceStore,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    progression: SkillProgression,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val itemDef = ensureNotNull(items.byId(command.item)) {
        WorldRejection.UnknownItem(command.item)
    }
    val cell = requireAvailableDeposit(command.agent, nodeId, command.item, resources, tick)

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.harvestStaminaCost(command.item)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    val quantity = balance.harvestYield(command.item).coerceAtMost(cell.quantity)

    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = state.inventoryOf(command.agent).totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = quantity * itemDef.weightPerUnit
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    resources.decrement(nodeId, command.item, quantity, tick)
    itemDef.harvestSkill?.let { skillKey ->
        progression.accrueXp(command.agent, SkillId(skillKey), delta = quantity, tick, command.commandId)
    }

    // TODO(max-stack): reject (StackFull) when adding `quantity` would exceed maxStack.
    // TODO(events): emit WorldEvent.NodeResourceDepleted alongside ResourceHarvested when
    //               this harvest takes the cell to zero — needs multi-event reducer return.
    val nextInventory = state.inventoryOf(command.agent).add(command.item, quantity)
    val next = state
        .updateBody(command.agent, body.spendStamina(cost))
        .updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ResourceHarvested(
        agent = command.agent,
        at = nodeId,
        item = command.item,
        quantity = quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}

/**
 * Splits the cell-lookup into two distinct rejections so the agent can tell "wrong place"
 * (no row — no spawn rule on this terrain, or the spawn-chance roll failed at paint time)
 * from "deposit gone" (row at zero — harvested out). Strategic responses differ.
 */
private fun Raise<WorldRejection>.requireAvailableDeposit(
    agent: AgentId,
    nodeId: NodeId,
    item: ItemId,
    resources: NodeResourceStore,
    tick: Long,
): NodeResourceCell {
    val cell = resources.availability(nodeId, item, tick)
        ?: raise(WorldRejection.ResourceNotAvailableHere(agent, nodeId, item))
    if (cell.quantity == 0) raise(WorldRejection.NodeResourceDepleted(agent, nodeId, item))
    return cell
}
