package dev.gvart.genesara.world.internal.say

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice

internal fun reduceSay(
    core: CoreSlice,
    command: CoreCommand.Say,
    balance: BalanceLookup,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val origin = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val max = balance.maxSayMessageLength()
    ensure(command.message.length <= max) {
        WorldRejection.MessageTooLong(command.agent, command.message.length, max)
    }

    val radius = balance.sayRangeFor(command.mode)
    val reachable = nodesWithin(core.nodes, origin, radius)
    val listeners = core.positions
        .asSequence()
        .filter { (_, node) -> node in reachable }
        .map { it.key }
        .toSet()

    val event = CoreEvent.AgentSpoke(
        speaker = command.agent,
        at = origin,
        message = command.message,
        mode = command.mode,
        channel = command.channel,
        listeners = listeners,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = core, events = listOf(event))
}

private fun nodesWithin(nodes: Map<NodeId, Node>, origin: NodeId, radius: Int): Set<NodeId> {
    if (radius < 0) return emptySet()
    val visited = mutableSetOf(origin)
    var frontier: Set<NodeId> = setOf(origin)
    repeat(radius) {
        val next = mutableSetOf<NodeId>()
        for (id in frontier) {
            val adj = nodes[id]?.adjacency ?: continue
            for (neighbour in adj) {
                if (visited.add(neighbour)) next += neighbour
            }
        }
        if (next.isEmpty()) return visited
        frontier = next
    }
    return visited
}
