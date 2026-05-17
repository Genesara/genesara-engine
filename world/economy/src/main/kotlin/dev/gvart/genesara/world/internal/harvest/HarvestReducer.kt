package dev.gvart.genesara.world.internal.harvest

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * Reducer for [EconomyCommand.Harvest].
 *
 * Mutates [NodeResourceStore.decrement] outside [WorldState] because per-node cells
 * are too large to load into the aggregate every tick. The caller (`WorldTickHandler`)
 * owns the surrounding transaction; if its tx rolls back, the decrement rolls back too.
 */
fun reduceHarvest(
    body: BodySlice,
    core: CoreReadView,
    command: EconomyCommand.Harvest,
    balance: BalanceLookup,
    items: ItemLookup,
    resources: NodeResourceStore,
    agents: AgentRegistry,
    equipment: AgentItemInstancesStore,
    progression: SkillProgression,
    characterXp: CharacterXpProgression,
    scaling: LevelScalingAggregator,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensureNotNull(core.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val itemDef = ensureNotNull(items.byId(command.item)) {
        WorldRejection.UnknownItem(command.item)
    }
    ensure(!itemDef.extractionOnly) {
        WorldRejection.HarvestRequiresExtraction(command.agent, nodeId, command.item)
    }
    val cell = requireAvailableDeposit(command.agent, nodeId, command.item, resources, tick)

    val agentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.harvestStaminaCost(command.item)
    ensure(agentBody.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, agentBody.stamina)
    }

    val yieldBonus = scaling.bonusFor(command.agent, ScalingEffect.HARVEST_YIELD_BONUS)
    val baseYield = balance.harvestYield(command.item)
    val scaledYield = (baseYield * (1.0 + yieldBonus)).toInt().coerceAtLeast(baseYield)
    val quantity = scaledYield.coerceAtMost(cell.quantity)

    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = body.inventoryOf(command.agent).totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = quantity * itemDef.weightPerUnit
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    resources.decrement(nodeId, command.item, quantity, tick)
    itemDef.harvestSkill?.let { skill ->
        progression.accrueXp(command.agent, skill, delta = quantity, tick, command.commandId, agentRecord.classId)
    }
    characterXp.grant(command.agent, CharacterXpSource.HARVEST, delta = quantity, tick = tick, commandId = command.commandId)
    behaviorTracker.record(command.agent, ActionCategory.GATHER, tick)

    // TODO(max-stack): reject (StackFull) when adding `quantity` would exceed maxStack.
    // TODO(events): emit WorldEvent.NodeResourceDepleted alongside ResourceHarvested when
    //               this harvest takes the cell to zero — needs multi-event reducer return.
    val nextInventory = body.inventoryOf(command.agent).add(command.item, quantity)
    val nextBody = body.copy(
        bodies = body.bodies + (command.agent to agentBody.spendStamina(cost)),
        inventories = body.inventories + (command.agent to nextInventory),
    )
    val event = EconomyEvent.ResourceHarvested(
        agent = command.agent,
        at = nodeId,
        item = command.item,
        quantity = quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    val triggered = triggeredPassives.dispatch(
        firer = command.agent,
        trigger = TriggeredPassiveTrigger.ON_HARVEST_COMPLETE,
        ctx = TriggerContext.None,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextBody, events = listOf(event) + triggered)
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
