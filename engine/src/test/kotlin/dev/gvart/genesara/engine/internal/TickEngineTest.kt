package dev.gvart.genesara.engine.internal

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TickEngineTest {

    @Test
    fun `currentTick starts at zero`() {
        assertEquals(0L, TickEngine().currentTick())
    }

    @Test
    fun `incrementAndGet advances the counter and currentTick reflects it`() {
        val engine = TickEngine()

        assertEquals(1L, engine.incrementAndGet())
        assertEquals(1L, engine.currentTick())
        assertEquals(2L, engine.incrementAndGet())
        assertEquals(2L, engine.currentTick())
    }

    @Test
    fun `concurrent increments do not lose updates`() {
        val engine = TickEngine()
        val threads = 8
        val perThread = 1_000
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) {
            executor.submit {
                start.await()
                repeat(perThread) { engine.incrementAndGet() }
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "writers did not finish in time")

        assertEquals((threads * perThread).toLong(), engine.currentTick())
    }
}
