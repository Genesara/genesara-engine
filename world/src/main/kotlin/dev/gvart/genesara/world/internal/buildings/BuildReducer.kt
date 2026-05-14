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
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Terrain
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

internal fun reduceBuild(
    state: WorldState,
    command: WorldCommand.BuildStructure,
    catalog: BuildingsCatalog,
    skills: AgentSkillsRegistry,
    buildings: BuildingsStore,
    bars: BuildingBarsStore,
    safeNodes: AgentSafeNodeGateway,
    plots: AgentPlotsStore,
    gateStates: BuildingGateStateStore,
    keys: AgentItemInstancesStore,
    progression: SkillProgression,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val node = ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }
    val def = catalog.def(command.type)
    val targetBar = resolveTargetBar(def, command)

    if (targetBar.level > 0) {
        val current = skills.snapshot(command.agent).perSkill[targetBar.skill]?.level ?: 0
        ensure(current >= targetBar.level) {
            WorldRejection.BuildingSkillTooLow(
                agent = command.agent,
                type = command.type,
                skill = targetBar.skill,
                required = targetBar.level,
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
    if (existing == null) {
        // Terrain coupling: MINE can only be founded on rocky terrain. The
        // restriction lives in the reducer (not the catalog YAML) because today
        // only MINE is terrain-coupled; if more types join we will lift this
        // into the catalog as a per-def `allowedTerrains` set.
        val allowed = terrainGate(command.type)
        if (allowed != null && node.terrain !in allowed) {
            raise(
                WorldRejection.BuildingTerrainMismatch(
                    agent = command.agent,
                    type = command.type,
                    node = nodeId,
                    terrain = node.terrain,
                    allowed = allowed,
                ),
            )
        }

        val occupant = buildings.findAnyAtNodeOfType(nodeId, command.type)
        if (occupant != null) {
            raise(
                WorldRejection.DuplicateBuildingAtNode(
                    agent = command.agent,
                    type = command.type,
                    node = nodeId,
                    existingInstanceId = occupant.instanceId,
                ),
            )
        }

        // DEFENSIVE slot is shared across WOODEN_WALL + GATE: stacking a wall
        // on top of a gate (or vice versa) is nonsensical (gate is bypassable,
        // wall isn't). At most one DEFENSIVE-category structure per node.
        if (def.categoryHint == BuildingCategoryHint.DEFENSIVE) {
            val occupyingDefensive = buildings.listAtNode(nodeId)
                .firstOrNull { catalog.def(it.type).categoryHint == BuildingCategoryHint.DEFENSIVE }
            if (occupyingDefensive != null) {
                raise(
                    WorldRejection.DefensiveAlreadyAtNode(
                        agent = command.agent,
                        type = command.type,
                        node = nodeId,
                        existingType = occupyingDefensive.type,
                        existingInstanceId = occupyingDefensive.instanceId,
                    ),
                )
            }
        }
    }

    val inventory = state.inventoryOf(command.agent)
    requireMaterials(command.agent, command.type, inventory, targetBar.materialsPerStep)
    val nextInventory = targetBar.materialsPerStep.entries
        .fold(inventory) { acc, (item, qty) -> acc.remove(item, qty) }

    val nextAggregate = (existing?.progressSteps ?: 0) + 1
    val isFinalStep = nextAggregate == def.totalSteps

    val (resultBuilding, event) = if (existing == null) {
        val instanceId = UUID.randomUUID()
        val placed = Building(
            instanceId = instanceId,
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
        bars.insertAll(
            def.skillBars.map { barDef ->
                BuildingBar(
                    instanceId = instanceId,
                    skill = barDef.skill,
                    progressSteps = if (barDef.skill == targetBar.skill) 1 else 0,
                    totalSteps = barDef.steps,
                )
            },
        )
        placed to progressedEvent(placed, command, tick)
    } else {
        val advancedBar = bars.advanceBar(existing.instanceId, targetBar.skill)
            ?: raise(
                WorldRejection.BarAlreadyComplete(
                    agent = command.agent,
                    type = command.type,
                    skill = targetBar.skill,
                ),
            )
        check(advancedBar.progressSteps <= advancedBar.totalSteps) {
            "Bar advanced past total: ${existing.instanceId} ${targetBar.skill}"
        }

        if (isFinalStep) {
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
            val advanced = buildings.advanceProgress(existing.instanceId, nextAggregate, tick)
                ?: error("Building ${existing.instanceId} vanished between findInProgress and advanceProgress")
            advanced to progressedEvent(advanced, command, tick)
        }
    }

    val completionEvents = if (event is WorldEvent.BuildingConstructed) {
        applyCompletionSideEffects(resultBuilding, safeNodes, plots, gateStates, keys, command.commandId, tick)
    } else {
        emptyList()
    }

    progression.accrueXp(command.agent, targetBar.skill, delta = 1, tick, command.commandId)
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
    next to (listOf(event) + completionEvents + triggered)
}

private val MINE_ALLOWED_TERRAINS: Set<Terrain> = setOf(Terrain.FOOTHILLS, Terrain.MOUNTAIN, Terrain.VOLCANIC)

private fun terrainGate(type: BuildingType): Set<Terrain>? = when (type) {
    BuildingType.MINE -> MINE_ALLOWED_TERRAINS
    else -> null
}

private fun Raise<WorldRejection>.resolveTargetBar(
    def: BuildingDef,
    command: WorldCommand.BuildStructure,
): BarDefinition {
    val skill = command.skill
    if (skill == null) {
        if (def.isSingleBar) return def.defaultBar()
        raise(WorldRejection.SkillRequiredForMultiBar(command.agent, command.type))
    }
    return def.bar(skill)
        ?: raise(WorldRejection.BarNotInBuilding(command.agent, command.type, skill))
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
    stepCost: Map<ItemId, Int>,
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
    plots: AgentPlotsStore,
    gateStates: BuildingGateStateStore,
    keys: AgentItemInstancesStore,
    commandId: UUID,
    tick: Long,
): List<WorldEvent> = when (building.type) {
    BuildingType.SHELTER -> {
        safeNodes.set(building.builtByAgentId, building.nodeId, tick)
        emptyList()
    }
    BuildingType.FARM_PLOT -> {
        plots.insertEmpty(
            AgentPlot(
                plotId = UUID.randomUUID(),
                buildingInstanceId = building.instanceId,
                nodeId = building.nodeId,
                plant = null,
            ),
        )
        emptyList()
    }
    BuildingType.GATE -> {
        gateStates.insertClosed(building.instanceId)
        val keyId = UUID.randomUUID()
        keys.insert(
            ItemInstance.Key(
                instanceId = keyId,
                agentId = building.builtByAgentId,
                itemId = ItemId("GATE_KEY"),
                gateInstanceId = building.instanceId,
                createdAtTick = tick,
            ),
        )
        listOf(
            WorldEvent.GateKeyMinted(
                agent = building.builtByAgentId,
                keyInstanceId = keyId,
                gateId = building.instanceId,
                byCopy = false,
                tick = tick,
                causedBy = commandId,
            ),
        )
    }
    else -> emptyList()
}
