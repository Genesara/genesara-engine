package dev.gvart.genesara.world.internal.movement

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView

internal fun reduceMove(
    core: CoreSlice,
    bodyView: BodyReadView,
    command: CoreCommand.MoveAgent,
    balance: BalanceLookup,
    buildings: BuildingsLookup,
    gateStates: BuildingGateStateStore,
    scaling: LevelScalingAggregator,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val from = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val toNode = ensureNotNull(core.nodes[command.to]) {
        WorldRejection.UnknownNode(command.to)
    }
    val toRegion = ensureNotNull(core.regions[toNode.regionId]) {
        WorldRejection.UnknownRegion(toNode.regionId)
    }
    ensure(core.nodes[from]?.adjacency?.contains(command.to) == true) {
        WorldRejection.NotAdjacent(from, command.to)
    }
    ensure(balance.isTraversable(toNode.terrain) || hasActiveBuilding(buildings, command.to, BuildingCategoryHint.INFRASTRUCTURE_BRIDGE)) {
        WorldRejection.TerrainNotTraversable(command.agent, command.to, toNode.terrain)
    }
    val defensive = buildings.activeStationsAt(command.to, BuildingCategoryHint.DEFENSIVE)
    if (defensive.isNotEmpty()) {
        val allOpenGates = defensive.all { it.type == BuildingType.GATE && gateStates.isOpen(it.instanceId) == true }
        ensure(allOpenGates) { WorldRejection.DefensiveBlocks(command.agent, command.to) }
    }
    val biome = ensureNotNull(toRegion.biome) { WorldRejection.UnpaintedRegion(toRegion.id) }
    val climate = ensureNotNull(toRegion.climate) { WorldRejection.UnpaintedRegion(toRegion.id) }

    val body = bodyView.bodyOf(command.agent)!!
    val baseCost = balance.moveStaminaCost(biome, climate, toNode.terrain)

    val onRoad = hasActiveBuilding(buildings, from, BuildingCategoryHint.INFRASTRUCTURE_ROAD) ||
        hasActiveBuilding(buildings, command.to, BuildingCategoryHint.INFRASTRUCTURE_ROAD)
    // Floor at 1 so road-hopping still costs stamina.
    val roadAdjusted = if (onRoad) (baseCost * balance.roadStaminaMultiplier()).toInt().coerceAtLeast(1) else baseCost
    val speedBonus = scaling.bonusFor(command.agent, ScalingEffect.MOVEMENT_SPEED)
    val cost = (roadAdjusted / (1.0 + speedBonus)).toInt().coerceAtLeast(1)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }
    val nextCore = core.copy(positions = core.positions + (command.agent to command.to))
    behaviorTracker.record(command.agent, ActionCategory.EXPLORE, tick)
    val event = CoreEvent.AgentMoved(
        agent = command.agent,
        from = from,
        to = command.to,
        staminaSpent = cost,
        tick = tick,
        causedBy = command.commandId,
    )
    val effects = listOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateBody(command.agent, body.spendStamina(cost)),
        CrossZoneEffect.MaybeSpawnLazyNpcs(command.to, command.agent, tick),
    )
    ReducerOutput(sliceDelta = nextCore, effects = effects, events = listOf(event))
}

private fun hasActiveBuilding(
    buildings: BuildingsLookup,
    node: NodeId,
    hint: BuildingCategoryHint,
): Boolean = buildings.activeStationsAt(node, hint).isNotEmpty()
