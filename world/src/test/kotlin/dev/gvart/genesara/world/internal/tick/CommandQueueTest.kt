package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.commands.WorldCommand
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandQueueTest {

    @Test
    fun `submit and drain return commands for the requested tick only`() {
        val queue = CommandQueue()
        val a = move(NodeId(1L))
        val b = move(NodeId(2L))
        val c = move(NodeId(3L))

        queue.submit(a, appliesAtTick = 5)
        queue.submit(b, appliesAtTick = 5)
        queue.submit(c, appliesAtTick = 6)

        val tick5 = queue.drainFor(5)
        assertEquals(listOf(a, b), tick5)
        // Other tick is untouched.
        assertEquals(listOf(c), queue.drainFor(6))
    }

    @Test
    fun `drain removes commands so a second drain at the same tick is empty`() {
        val queue = CommandQueue()
        queue.submit(move(NodeId(1L)), appliesAtTick = 10)

        assertEquals(1, queue.drainFor(10).size)
        assertTrue(queue.drainFor(10).isEmpty())
    }

    @Test
    fun `drain for a tick with no commands returns an empty list`() {
        val queue = CommandQueue()
        assertTrue(queue.drainFor(99).isEmpty())
    }

    @Test
    fun `concurrent submits to the same tick do not lose commands`() {
        val queue = CommandQueue()
        val threads = 8
        val perThread = 200
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)

        repeat(threads) {
            executor.submit {
                latch.await()
                repeat(perThread) {
                    queue.submit(move(NodeId(it.toLong())), appliesAtTick = 1)
                }
            }
        }
        latch.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "submitters did not finish in time")

        assertEquals(threads * perThread, queue.drainFor(1).size)
    }

    private fun move(to: NodeId): WorldCommand.MoveAgent =
        WorldCommand.MoveAgent(agent = AgentId(UUID.randomUUID()), to = to)
}
