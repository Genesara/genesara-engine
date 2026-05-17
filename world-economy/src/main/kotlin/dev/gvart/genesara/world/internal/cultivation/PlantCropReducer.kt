package dev.gvart.genesara.world.internal.cultivation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.PlantedCrop
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.cultivation.CropLookupImpl.Companion.FARMING_SKILL
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

internal fun reducePlantCrop(
    body: BodySlice,
    core: CoreReadView,
    command: EconomyCommand.PlantCrop,
    crops: CropLookup,
    plots: AgentPlotsStore,
    agents: AgentRegistry,
    skills: AgentSkillsRegistry,
    progression: SkillProgression,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val node = ensureNotNull(core.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val crop = ensureNotNull(crops.byId(command.crop)) {
        WorldRejection.UnknownCrop(command.agent, command.crop)
    }

    val plot = ensureNotNull(plots.findById(command.plotId)) {
        WorldRejection.UnknownPlot(command.agent, command.plotId)
    }
    ensure(plot.nodeId == nodeId) {
        WorldRejection.NotOnPlotNode(command.agent, command.plotId, nodeId, plot.nodeId)
    }
    plot.plant?.let { existing ->
        raise(WorldRejection.PlotNotEmpty(command.agent, command.plotId, existing.cropId))
    }

    ensure(node.terrain in crop.requiredTerrain) {
        WorldRejection.CropTerrainMismatch(
            agent = command.agent,
            plotId = command.plotId,
            crop = command.crop,
            terrain = node.terrain,
            allowed = crop.requiredTerrain,
        )
    }

    val farmingLevel = skills.snapshot(command.agent).perSkill[FARMING_SKILL]?.level ?: 0
    ensure(farmingLevel >= crop.requiredFarmingLevel) {
        WorldRejection.CropFarmingLevelTooLow(
            agent = command.agent,
            crop = command.crop,
            required = crop.requiredFarmingLevel,
            current = farmingLevel,
        )
    }

    val inventory = body.inventoryOf(command.agent)
    ensure(inventory.quantityOf(crop.seedItem) >= 1) {
        WorldRejection.MissingSeed(command.agent, command.crop, crop.seedItem)
    }

    val agentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(agentBody.stamina >= crop.staminaCostPlant) {
        WorldRejection.NotEnoughStamina(command.agent, crop.staminaCostPlant, agentBody.stamina)
    }

    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")

    plots.plant(
        plotId = command.plotId,
        crop = PlantedCrop(
            cropId = command.crop,
            plantedAtTick = tick,
            lastTendedAtTick = tick,
            plantedByAgentId = command.agent,
        ),
    ) ?: error("Plot ${command.plotId} vanished or filled between findById and plant")

    progression.accrueXp(command.agent, FARMING_SKILL, delta = 1, tick, command.commandId, agentRecord.classId)
    behaviorTracker.record(command.agent, ActionCategory.GATHER, tick)

    val nextInventory = inventory.remove(crop.seedItem, 1)
    val nextBody = body.copy(
        bodies = body.bodies + (command.agent to agentBody.spendStamina(crop.staminaCostPlant)),
        inventories = body.inventories + (command.agent to nextInventory),
    )
    val event = EconomyEvent.CropPlanted(
        agent = command.agent,
        at = nodeId,
        plotId = command.plotId,
        crop = command.crop,
        plantedAtTick = tick,
        ripeAtTick = tick + crop.ticksToRipe,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextBody, events = listOf<WorldEvent>(event))
}

/**
 * Transitional wrapper preserving the legacy (WorldState) signature.
 */
internal fun reducePlantCrop(
    state: WorldState,
    command: EconomyCommand.PlantCrop,
    crops: CropLookup,
    plots: AgentPlotsStore,
    agents: AgentRegistry,
    skills: AgentSkillsRegistry,
    progression: SkillProgression,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> =
    reducePlantCrop(
        state.body, state.core, command, crops, plots, agents, skills, progression, behaviorTracker, tick,
    ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
