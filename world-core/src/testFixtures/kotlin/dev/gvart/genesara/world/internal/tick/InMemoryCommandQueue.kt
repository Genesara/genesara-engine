package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
import java.util.concurrent.ConcurrentHashMap

/**
 * Test double for the Redis command queue. Single-world by default — tests
 * that don't pass a world context use [DEFAULT_WORLD] both for submit and
 * drain. Multi-world tests can call [submitTo] / [drainFor] explicitly.
 */
internal class InMemoryCommandQueue(
    private val defaultWorld: WorldId = DEFAULT_WORLD,
) : WorldCommandGateway, WorldCommandDrainer {

    private val byWorldTick = ConcurrentHashMap<Pair<Long, Long>, MutableList<WorldCommand>>()

    override fun submit(command: WorldCommand, appliesAtTick: Long): Long {
        submitTo(defaultWorld, command, appliesAtTick)
        return appliesAtTick
    }

    fun submitTo(worldId: WorldId, command: WorldCommand, appliesAtTick: Long) {
        byWorldTick
            .computeIfAbsent(worldId.value to appliesAtTick) { mutableListOf() }
            .add(command)
    }

    override fun drainFor(worldId: WorldId, tick: Long): List<WorldCommand> =
        byWorldTick.remove(worldId.value to tick).orEmpty()

    companion object {
        val DEFAULT_WORLD = WorldId(1L)
    }
}
