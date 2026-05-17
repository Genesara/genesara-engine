package dev.gvart.genesara.world.internal.cultivation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.cultivation.CropLookupImpl.Companion.FARMING_SKILL
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import kotlin.random.Random

fun reduceHarvestCrop(
    body: BodySlice,
    core: CoreReadView,
    command: EconomyCommand.HarvestCrop,
    crops: CropLookup,
    plots: AgentPlotsStore,
    items: ItemLookup,
    agents: AgentRegistry,
    skills: AgentSkillsRegistry,
    equipment: AgentItemInstancesStore,
    balance: BalanceLookup,
    progression: SkillProgression,
    characterXp: CharacterXpProgression,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    rng: Random,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }

    val plot = ensureNotNull(plots.findById(command.plotId)) {
        WorldRejection.UnknownPlot(command.agent, command.plotId)
    }
    ensure(plot.nodeId == nodeId) {
        WorldRejection.NotOnPlotNode(command.agent, command.plotId, nodeId, plot.nodeId)
    }
    val planted = plot.plant ?: raise(WorldRejection.PlotEmpty(command.agent, command.plotId))
    val crop = ensureNotNull(crops.byId(planted.cropId)) {
        WorldRejection.UnknownCrop(command.agent, planted.cropId)
    }

    val ripeAtTick = planted.plantedAtTick + crop.ticksToRipe
    ensure(tick >= ripeAtTick) {
        WorldRejection.CropNotRipe(
            agent = command.agent,
            plotId = command.plotId,
            crop = planted.cropId,
            ticksRemaining = ripeAtTick - tick,
        )
    }

    val itemDef = ensureNotNull(items.byId(crop.outputItem)) {
        WorldRejection.UnknownItem(crop.outputItem)
    }

    val agentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(agentBody.stamina >= crop.staminaCostHarvest) {
        WorldRejection.NotEnoughStamina(command.agent, crop.staminaCostHarvest, agentBody.stamina)
    }

    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")

    val farmingLevel = skills.snapshot(command.agent).perSkill[FARMING_SKILL]?.level ?: 0
    val skillBonus = (farmingLevel * crop.gainPerLevel).toInt()
    val luckBonus = if (crop.maxLuckBonus > 0) rng.nextInt(0, crop.maxLuckBonus + 1) else 0
    val quantity = (crop.baseYield + skillBonus + luckBonus).coerceAtLeast(1)

    val currentGrams = body.inventoryOf(command.agent).totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = quantity * itemDef.weightPerUnit
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    plots.clearPlanting(command.plotId)
        ?: error("Plot ${command.plotId} vanished or cleared between findById and clearPlanting")

    progression.accrueXp(command.agent, FARMING_SKILL, delta = quantity, tick, command.commandId, agentRecord.classId)
    characterXp.grant(command.agent, CharacterXpSource.HARVEST, delta = quantity, tick = tick, commandId = command.commandId)
    behaviorTracker.record(command.agent, ActionCategory.GATHER, tick)

    val nextInventory = body.inventoryOf(command.agent).add(crop.outputItem, quantity)
    val nextBody = body.copy(
        bodies = body.bodies + (command.agent to agentBody.spendStamina(crop.staminaCostHarvest)),
        inventories = body.inventories + (command.agent to nextInventory),
    )
    val event = EconomyEvent.CropHarvested(
        agent = command.agent,
        at = nodeId,
        plotId = command.plotId,
        crop = planted.cropId,
        outputItem = crop.outputItem,
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
    ReducerOutput(sliceDelta = nextBody, events = listOf<WorldEvent>(event) + triggered)
}

/**
 * Transitional wrapper preserving the legacy (WorldState) signature.
 */
fun reduceHarvestCrop(
    state: WorldState,
    command: EconomyCommand.HarvestCrop,
    crops: CropLookup,
    plots: AgentPlotsStore,
    items: ItemLookup,
    agents: AgentRegistry,
    skills: AgentSkillsRegistry,
    equipment: AgentItemInstancesStore,
    balance: BalanceLookup,
    progression: SkillProgression,
    characterXp: CharacterXpProgression,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    rng: Random,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reduceHarvestCrop(
        state.body, state.core, command, crops, plots, items, agents, skills, equipment, balance,
        progression, characterXp, triggeredPassives, behaviorTracker, rng, tick,
    ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
