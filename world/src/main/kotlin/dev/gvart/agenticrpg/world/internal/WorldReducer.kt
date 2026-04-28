package dev.gvart.agenticrpg.world.internal

import arrow.core.Either
import dev.gvart.agenticrpg.player.AgentProfileLookup
import dev.gvart.agenticrpg.world.WorldRejection
import dev.gvart.agenticrpg.world.commands.WorldCommand
import dev.gvart.agenticrpg.world.events.WorldEvent
import dev.gvart.agenticrpg.world.internal.balance.BalanceLookup
import dev.gvart.agenticrpg.world.internal.movement.reduceMove
import dev.gvart.agenticrpg.world.internal.spawn.reduceSpawn
import dev.gvart.agenticrpg.world.internal.spawn.reduceUnspawn
import dev.gvart.agenticrpg.world.internal.worldstate.WorldState

internal fun reduce(
    state: WorldState,
    command: WorldCommand,
    balance: BalanceLookup,
    profiles: AgentProfileLookup,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = when (command) {
    is WorldCommand.SpawnAgent -> reduceSpawn(state, command, profiles, tick)
    is WorldCommand.MoveAgent -> reduceMove(state, command, balance, tick)
    is WorldCommand.UnspawnAgent -> reduceUnspawn(state, command, tick)
}
