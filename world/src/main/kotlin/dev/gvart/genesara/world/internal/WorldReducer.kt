package dev.gvart.genesara.world.internal

import arrow.core.Either
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.consume.reduceConsume
import dev.gvart.genesara.world.internal.drink.reduceDrink
import dev.gvart.genesara.world.internal.gather.reduceGather
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.reduceSpawn
import dev.gvart.genesara.world.internal.spawn.reduceUnspawn
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduce(
    state: WorldState,
    command: WorldCommand,
    balance: BalanceLookup,
    profiles: AgentProfileLookup,
    items: ItemLookup,
    resources: NodeResourceStore,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = when (command) {
    is WorldCommand.SpawnAgent -> reduceSpawn(state, command, profiles, tick)
    is WorldCommand.MoveAgent -> reduceMove(state, command, balance, tick)
    is WorldCommand.UnspawnAgent -> reduceUnspawn(state, command, tick)
    is WorldCommand.GatherResource -> reduceGather(state, command, balance, items, resources, tick)
    is WorldCommand.ConsumeItem -> reduceConsume(state, command, items, tick)
    is WorldCommand.Drink -> reduceDrink(state, command, balance, tick)
}
