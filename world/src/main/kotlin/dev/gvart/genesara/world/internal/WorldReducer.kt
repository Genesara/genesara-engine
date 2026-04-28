package dev.gvart.genesara.world.internal

import arrow.core.Either
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.internal.spawn.reduceSpawn
import dev.gvart.genesara.world.internal.spawn.reduceUnspawn
import dev.gvart.genesara.world.internal.worldstate.WorldState

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
