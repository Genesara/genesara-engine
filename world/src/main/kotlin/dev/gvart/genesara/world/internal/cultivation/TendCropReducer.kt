package dev.gvart.genesara.world.internal.cultivation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.cultivation.CropLookupImpl.Companion.FARMING_SKILL
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduceTendCrop(
    state: WorldState,
    command: WorldCommand.TendCrop,
    crops: CropLookup,
    plots: AgentPlotsStore,
    agents: AgentRegistry,
    progression: SkillProgression,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
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
        // Catalog dropped this crop after plant; the per-tick decay sweep will clear the row.
        WorldRejection.UnknownCrop(command.agent, planted.cropId)
    }

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(body.stamina >= crop.staminaCostTend) {
        WorldRejection.NotEnoughStamina(command.agent, crop.staminaCostTend, body.stamina)
    }

    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")

    plots.tend(command.plotId, tick)
        ?: error("Plot ${command.plotId} vanished or cleared between findById and tend")

    progression.accrueXp(command.agent, FARMING_SKILL, delta = 1, tick, command.commandId, agentRecord.classId)
    behaviorTracker.record(command.agent, ActionCategory.GATHER, tick)

    val next = state.updateBody(command.agent, body.spendStamina(crop.staminaCostTend))
    val event = WorldEvent.CropTended(
        agent = command.agent,
        at = nodeId,
        plotId = command.plotId,
        crop = planted.cropId,
        tick = tick,
        causedBy = command.commandId,
    )
    next to listOf<WorldEvent>(event)
}
