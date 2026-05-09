package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.AgentId
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

    /**
     * Selective drain — takes only commands whose agent is in [forAgents],
     * leaves the rest in the bucket for sibling world handlers ticking at
     * the same number. Required for single-pod multi-world correctness:
     * without it, the first handler to land on a given tick steals every
     * command in the bucket, including those targeted at agents in other
     * worlds. #82 supersedes this with a Redis-per-world queue.
     */
    fun drainFor(tick: Long, forAgents: Set<AgentId>): List<WorldCommand> {
        if (forAgents.isEmpty()) return emptyList()
        val bucket = byTick[tick] ?: return emptyList()
        val taken = mutableListOf<WorldCommand>()
        val iterator = bucket.iterator()
        while (iterator.hasNext()) {
            val command = iterator.next()
            if (command.agent in forAgents) {
                taken += command
                iterator.remove()
            }
        }
        if (bucket.isEmpty()) byTick.remove(tick, bucket)
        return taken
    }
}
