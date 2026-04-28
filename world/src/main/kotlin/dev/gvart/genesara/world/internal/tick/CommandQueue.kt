package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.stereotype.Component
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Component
internal class CommandQueue : WorldCommandGateway {

    private val byTick = ConcurrentHashMap<Long, Queue<WorldCommand>>()

    override fun submit(command: WorldCommand, appliesAtTick: Long) {
        byTick.computeIfAbsent(appliesAtTick) { ConcurrentLinkedQueue() }.add(command)
    }

    fun drainFor(tick: Long): List<WorldCommand> =
        byTick.remove(tick)?.toList().orEmpty()
}
