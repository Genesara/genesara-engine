package dev.gvart.genesara.world.internal.movement

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcType
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
import dev.gvart.genesara.world.internal.worldstate.views.EnvironmentReadView

fun reduceMove(
    core: CoreSlice,
    bodyView: BodyReadView,
    command: CoreCommand.MoveAgent,
    balance: BalanceLookup,
    buildings: BuildingsLookup,
    gateStates: BuildingGateStateStore,
    scaling: LevelScalingAggregator,
    behaviorTracker: BehaviorTracker,
    tick: Long,
    mounts: MountInstanceStore = MountInstanceStore.NoOp,
    mountCatalog: MountCatalog = MountCatalog.NoOp,
    environment: EnvironmentReadView,
    npcCatalog: NpcCatalog,
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
    val agentCost = (roadAdjusted / (1.0 + speedBonus)).toInt().coerceAtLeast(1)

    val destinationThreatHint = computeDestinationThreatHint(core, environment, npcCatalog, command.to)

    val ridden = mounts.findByRider(command.agent)
    val nextCore: CoreSlice
    val effects: List<CrossZoneEffect>
    if (ridden != null) {
        // Mounted: charge mount fatigue, NOT agent stamina. The mount moves
        // with the rider — its node_id follows. Cost scaled by the mount's
        // speedFactor (lower factor = faster mount = cheaper move). SADDLE
        // mount-gear bonus subtracts from the per-move fatigue cost
        // (floor 1).
        val mountDef = mountCatalog.byType(ridden.type)
        val rawMountedCost = if (mountDef != null) {
            (agentCost * mountDef.speedFactor).toInt().coerceAtLeast(1)
        } else {
            agentCost
        }
        val mountedCost = (rawMountedCost - ridden.saddleSpeedBonus).coerceAtLeast(1)
        ensure(ridden.fatigue >= mountedCost) {
            WorldRejection.NotEnoughMountFatigue(command.agent, ridden.id, mountedCost, ridden.fatigue)
        }
        mounts.update(
            ridden.copy(
                nodeId = command.to,
                fatigue = (ridden.fatigue - mountedCost).coerceAtLeast(0),
            ),
        )
        nextCore = core.copy(positions = core.positions + (command.agent to command.to))
        effects = listOf(CrossZoneEffect.MaybeSpawnLazyNpcs(command.to, command.agent, tick))
        behaviorTracker.record(command.agent, ActionCategory.EXPLORE, tick)
        val event = CoreEvent.AgentMoved(
            agent = command.agent,
            from = from,
            to = command.to,
            staminaSpent = 0,
            tick = tick,
            causedBy = command.commandId,
            destinationThreatHint = destinationThreatHint,
        )
        return@either ReducerOutput(sliceDelta = nextCore, effects = effects, events = listOf(event))
    }

    // On-foot path.
    ensure(body.stamina >= agentCost) {
        WorldRejection.NotEnoughStamina(command.agent, agentCost, body.stamina)
    }
    nextCore = core.copy(positions = core.positions + (command.agent to command.to))
    behaviorTracker.record(command.agent, ActionCategory.EXPLORE, tick)
    val event = CoreEvent.AgentMoved(
        agent = command.agent,
        from = from,
        to = command.to,
        staminaSpent = agentCost,
        tick = tick,
        causedBy = command.commandId,
        destinationThreatHint = destinationThreatHint,
    )
    effects = listOf(
        CrossZoneEffect.UpdateBody(command.agent, body.spendStamina(agentCost)),
        CrossZoneEffect.MaybeSpawnLazyNpcs(command.to, command.agent, tick),
    )
    ReducerOutput(sliceDelta = nextCore, effects = effects, events = listOf(event))
}

private fun hasActiveBuilding(
    buildings: BuildingsLookup,
    node: NodeId,
    hint: BuildingCategoryHint,
): Boolean = buildings.activeStationsAt(node, hint).isNotEmpty()

private fun computeDestinationThreatHint(
    core: CoreSlice,
    environment: EnvironmentReadView,
    npcCatalog: NpcCatalog,
    destination: NodeId,
): List<NpcType> {
    val npcs = environment.npcsAt(destination)
    if (npcs.isEmpty()) return emptyList()
    return npcs
        .filter { it.isThreatAt(destination, core, npcCatalog) }
        .map { it.type }
}

private fun Npc.isThreatAt(node: NodeId, core: CoreSlice, npcCatalog: NpcCatalog): Boolean {
    if (isDead) return false
    val def = npcCatalog.byType(type) ?: return false
    return when (def.aggressionProfile) {
        AggressionProfile.HOSTILE -> true
        AggressionProfile.TERRITORIAL ->
            hopDistance(core, node, spawnNodeId, def.territoryRadius) >= 0
        AggressionProfile.PASSIVE -> false
    }
}

