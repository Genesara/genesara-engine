package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.lease.LeaseLost
import dev.gvart.genesara.world.internal.tick.lease.LeasedWorlds
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldTickFanOutTest {

    @Test
    fun `empty lease set is a no-op — counter and runner are not touched`() {
        val counter = SequentialCounter()
        val runner = RecordingRunner()

        WorldTickFanOut(StubLeased(emptyList()), counter, runner).tickAllLeasedWorlds()

        assertEquals(0, counter.totalIncrements())
        assertTrue(runner.attempted.isEmpty())
    }

    @Test
    fun `each leased world is ticked exactly once with its incremented counter value`() {
        val worlds = (1L..3L).map { WorldId(it) }
        val counter = SequentialCounter()
        val runner = RecordingRunner()

        WorldTickFanOut(StubLeased(worlds), counter, runner).tickAllLeasedWorlds()

        assertEquals(worlds.toSet(), runner.completed)
        for (worldId in worlds) {
            assertEquals(listOf(1L), runner.numbersFor(worldId))
        }
    }

    @Test
    fun `world A failure does not cancel ticks for world B and C`() {
        val a = WorldId(1L)
        val b = WorldId(2L)
        val c = WorldId(3L)
        val runner = RecordingRunner(failOn = setOf(a))

        WorldTickFanOut(StubLeased(listOf(a, b, c)), SequentialCounter(), runner).tickAllLeasedWorlds()

        assertEquals(setOf(b, c), runner.completed)
        assertTrue(a in runner.attempted)
    }

    @Test
    fun `LeaseLost is swallowed and the rest of the fan-out completes`() {
        val a = WorldId(1L)
        val b = WorldId(2L)
        val runner = RecordingRunner(loseLeaseFor = setOf(a))

        WorldTickFanOut(StubLeased(listOf(a, b)), SequentialCounter(), runner).tickAllLeasedWorlds()

        assertEquals(setOf(b), runner.completed)
    }

    @Test
    fun `worlds tick in parallel — both enter the runner before either returns`() {
        val a = WorldId(1L)
        val b = WorldId(2L)
        val barrier = CountDownLatch(2)
        val runner = LatchedRunner(barrier)

        WorldTickFanOut(StubLeased(listOf(a, b)), SequentialCounter(), runner).tickAllLeasedWorlds()

        assertEquals(setOf(a, b), runner.completed)
        assertNotEquals(runner.threadFor(a), runner.threadFor(b))
    }

    private class StubLeased(private val ids: List<WorldId>) : LeasedWorlds {
        override fun held(): List<WorldId> = ids
    }

    private class SequentialCounter : WorldTickCounter {
        private val perWorld = ConcurrentHashMap<Long, AtomicLong>()
        override fun incrementAndGet(worldId: WorldId): Long =
            perWorld.computeIfAbsent(worldId.value) { AtomicLong() }.incrementAndGet()
        override fun currentTick(worldId: WorldId): Long =
            perWorld[worldId.value]?.get() ?: 0L
        override fun onLeaseAcquired(worldId: WorldId) = Unit
        fun totalIncrements(): Int = perWorld.values.sumOf { it.get().toInt() }
    }

    private open class RecordingRunner(
        private val failOn: Set<WorldId> = emptySet(),
        private val loseLeaseFor: Set<WorldId> = emptySet(),
    ) : WorldTickRunner {
        val attempted: MutableSet<WorldId> = ConcurrentHashMap.newKeySet()
        val completed: MutableSet<WorldId> = ConcurrentHashMap.newKeySet()
        private val numbers = ConcurrentHashMap<Long, MutableList<Long>>()

        override fun tickOne(worldId: WorldId, number: Long) {
            attempted += worldId
            numbers.computeIfAbsent(worldId.value) { java.util.Collections.synchronizedList(mutableListOf()) } += number
            if (worldId in loseLeaseFor) throw LeaseLost(worldId, number)
            if (worldId in failOn) error("boom for $worldId")
            onComplete(worldId, number)
            completed += worldId
        }

        protected open fun onComplete(worldId: WorldId, number: Long) = Unit

        fun numbersFor(worldId: WorldId): List<Long> = numbers[worldId.value]?.toList() ?: emptyList()
    }

    private class LatchedRunner(private val barrier: CountDownLatch) : RecordingRunner() {
        private val threads = ConcurrentHashMap<Long, String>()

        override fun onComplete(worldId: WorldId, number: Long) {
            threads[worldId.value] = Thread.currentThread().name
            barrier.countDown()
            assertTrue(barrier.await(2, TimeUnit.SECONDS), "fan-out is not parallel")
        }

        fun threadFor(worldId: WorldId): String = threads[worldId.value]!!
    }
}
