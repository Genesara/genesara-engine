package dev.gvart.genesara.world.internal.movement

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduceMove(
    state: WorldState,
    command: WorldCommand.MoveAgent,
    balance: BalanceLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val from = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.UnknownAgent(command.agent)
    }
    val toNode = ensureNotNull(state.nodes[command.to]) {
        WorldRejection.UnknownNode(command.to)
    }
    val toRegion = ensureNotNull(state.regions[toNode.regionId]) {
        WorldRejection.UnknownRegion(toNode.regionId)
    }
    ensure(state.isAdjacent(from, command.to)) {
        WorldRejection.NotAdjacent(from, command.to)
    }
    ensure(balance.isTraversable(toNode.terrain)) {
        WorldRejection.TerrainNotTraversable(command.agent, command.to, toNode.terrain)
    }
    val biome = ensureNotNull(toRegion.biome) { WorldRejection.UnpaintedRegion(toRegion.id) }
    val climate = ensureNotNull(toRegion.climate) { WorldRejection.UnpaintedRegion(toRegion.id) }

    val body = state.bodyOf(command.agent)!!
    val cost = balance.moveStaminaCost(biome, climate, toNode.terrain)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }
    val next = state
        .moveAgent(command.agent, command.to)
        .updateBody(command.agent, body.spendStamina(cost))
    val event = WorldEvent.AgentMoved(command.agent, from, command.to, tick, causedBy = command.commandId)

    next to event
}
