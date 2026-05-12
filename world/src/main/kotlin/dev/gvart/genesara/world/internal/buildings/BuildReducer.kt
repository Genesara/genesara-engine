package dev.gvart.genesara.world.internal.buildings

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID

/**
 * Find-or-create-then-advance reducer for [WorldCommand.BuildStructure]. Catalog
 * enforces `totalSteps >= 2`, so the "insert at progress=1, then complete same call"
 * trap (would violate the schema CHECK) cannot arise from a YAML-loaded def.
 */
internal fun reduceBuild(
    state: WorldState,
    command: WorldCommand.BuildStructure,
    catalog: BuildingsCatalog,
    skills: AgentSkillsRegistry,
    buildings: BuildingsStore,
    safeNodes: AgentSafeNodeGateway,
    progression: SkillProgression,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val def = catalog.def(command.type)

    if (def.requiredSkillLevel > 0) {
        val current = skills.snapshot(command.agent).perSkill[def.requiredSkill]?.level ?: 0
        ensure(current >= def.requiredSkillLevel) {
            WorldRejection.BuildingSkillTooLow(
                agent = command.agent,
                type = command.type,
                skill = def.requiredSkill,
                required = def.requiredSkillLevel,
                current = current,
            )
        }
    }

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(body.stamina >= def.staminaPerStep) {
        WorldRejection.NotEnoughStamina(command.agent, def.staminaPerStep, body.stamina)
    }

    val existing = buildings.findInProgress(nodeId, command.agent, command.type)
    val nextProgress = (existing?.progressSteps ?: 0) + 1
    val stepCost = def.stepMaterials[nextProgress - 1]
    val inventory = state.inventoryOf(command.agent)
    requireMaterials(command.agent, command.type, inventory, stepCost)

    val nextInventory = stepCost.entries.fold(inventory) { acc, (item, qty) -> acc.remove(item, qty) }
    val isFinalStep = nextProgress == def.totalSteps

    val (resultBuilding, event) = if (existing == null) {
        val placed = Building(
            instanceId = UUID.randomUUID(),
            nodeId = nodeId,
            type = command.type,
            status = BuildingStatus.UNDER_CONSTRUCTION,
            builtByAgentId = command.agent,
            builtAtTick = tick,
            lastProgressTick = tick,
            progressSteps = 1,
            totalSteps = def.totalSteps,
            hpCurrent = def.hp,
            hpMax = def.hp,
        )
        buildings.insert(placed)
        placed to progressedEvent(placed, command, tick)
    } else if (isFinalStep) {
        val completed = buildings.complete(existing.instanceId, tick)
            ?: error("Building ${existing.instanceId} vanished between findInProgress and complete")
        completed to WorldEvent.BuildingConstructed(
            agent = command.agent,
            instanceId = completed.instanceId,
            type = completed.type,
            at = completed.nodeId,
            totalSteps = completed.totalSteps,
            tick = tick,
            causedBy = command.commandId,
        )
    } else {
        val advanced = buildings.advanceProgress(existing.instanceId, nextProgress, tick)
            ?: error("Building ${existing.instanceId} vanished between findInProgress and advanceProgress")
        advanced to progressedEvent(advanced, command, tick)
    }

    if (event is WorldEvent.BuildingConstructed) applyCompletionSideEffects(resultBuilding, safeNodes, tick)

    progression.accrueXp(command.agent, def.requiredSkill, delta = 1, tick, command.commandId)
    behaviorTracker.record(command.agent, ActionCategory.BUILD, tick)

    val next = state
        .updateBody(command.agent, body.spendStamina(def.staminaPerStep))
        .updateInventory(command.agent, nextInventory)
    val triggered = if (event is WorldEvent.BuildingConstructed) {
        triggeredPassives.dispatch(
            firer = command.agent,
            trigger = TriggeredPassiveTrigger.ON_BUILD_COMPLETE,
            ctx = TriggerContext.None,
            tick = tick,
            causedBy = command.commandId,
        )
    } else {
        emptyList()
    }
    next to (listOf(event) + triggered)
}

private fun progressedEvent(
    building: Building,
    command: WorldCommand.BuildStructure,
    tick: Long,
): WorldEvent.BuildingProgressed = WorldEvent.BuildingProgressed(
    agent = command.agent,
    instanceId = building.instanceId,
    type = building.type,
    at = building.nodeId,
    step = building.progressSteps,
    totalSteps = building.totalSteps,
    tick = tick,
    causedBy = command.commandId,
)

private fun Raise<WorldRejection>.requireMaterials(
    agent: AgentId,
    type: BuildingType,
    inventory: AgentInventory,
    stepCost: Map<dev.gvart.genesara.world.ItemId, Int>,
) {
    for ((item, required) in stepCost) {
        val have = inventory.quantityOf(item)
        if (have < required) {
            raise(WorldRejection.InsufficientMaterials(agent, type, item, required, have))
        }
    }
}

private fun applyCompletionSideEffects(
    building: Building,
    safeNodes: AgentSafeNodeGateway,
    tick: Long,
) {
    when (building.type) {
        BuildingType.SHELTER -> safeNodes.set(building.builtByAgentId, building.nodeId, tick)
        else -> Unit
    }
}

