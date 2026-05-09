package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.lease.KnownWorlds
import dev.gvart.genesara.world.internal.tick.lease.LeaseManager
import dev.gvart.genesara.world.internal.tick.lease.RedisWorldLeaseStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class WorldTickFanOutIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

        private const val PER_WORLD_TICK_MS = 200L
        private const val WORLD_COUNT = 5
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisWorldLeaseStore

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        store = RedisWorldLeaseStore(template, ttl = Duration.ofSeconds(10), tickInterval = Duration.ofSeconds(5))
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `fan-out across 5 leased worlds completes in well under sequential wall-clock`() {
        val worlds = (1L..WORLD_COUNT.toLong()).map { WorldId(it) }
        val manager = newManager(worlds)
        manager.discover()
        assertEquals(worlds.toSet(), manager.held().toSet())

        val runner = SleepingRunner(perWorldMs = PER_WORLD_TICK_MS)
        val fanOut = WorldTickFanOut(manager, InMemoryCounter(), runner)

        val elapsed = measureMillis { fanOut.tickAllLeasedWorlds() }
        val sequentialBound = PER_WORLD_TICK_MS * WORLD_COUNT

        assertEquals(worlds.toSet(), runner.completed)
        assertTrue(
            elapsed < sequentialBound,
            "expected fan-out to complete in under ${sequentialBound}ms (5 worlds × ${PER_WORLD_TICK_MS}ms), took ${elapsed}ms",
        )
    }

    @Test
    fun `failure in one leased world does not abort ticks for the other leased worlds`() {
        val a = WorldId(1L)
        val b = WorldId(2L)
        val c = WorldId(3L)
        val worlds = listOf(a, b, c)
        val manager = newManager(worlds)
        manager.discover()
        assertEquals(worlds.toSet(), manager.held().toSet())

        val runner = SleepingRunner(perWorldMs = 50L, failOn = setOf(b))
        WorldTickFanOut(manager, InMemoryCounter(), runner).tickAllLeasedWorlds()

        assertEquals(setOf(a, c), runner.completed)
        assertTrue(b in runner.attempted)
    }

    @Test
    fun `each world's counter is incremented exactly once per fan-out cycle`() {
        val worlds = (1L..3L).map { WorldId(it) }
        val manager = newManager(worlds)
        manager.discover()

        val counter = InMemoryCounter()
        val runner = SleepingRunner(perWorldMs = 50L)
        val fanOut = WorldTickFanOut(manager, counter, runner)

        fanOut.tickAllLeasedWorlds()
        fanOut.tickAllLeasedWorlds()

        for (worldId in worlds) {
            assertEquals(listOf(1L, 2L), runner.numbersFor(worldId))
        }
    }

    private fun newManager(worlds: List<WorldId>): LeaseManager =
        LeaseManager(
            store = store,
            knownWorlds = StubKnownWorlds(worlds),
            podId = UUID.randomUUID().toString(),
            counter = InMemoryCounter(),
            maxPerPod = 1024,
        )

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - start).toMillis()
    }

    private class StubKnownWorlds(private val ids: List<WorldId>) : KnownWorlds {
        override fun all(): List<WorldId> = ids
    }

    private class InMemoryCounter : WorldTickCounter {
        private val perWorld = ConcurrentHashMap<Long, AtomicLong>()
        override fun incrementAndGet(worldId: WorldId): Long =
            perWorld.computeIfAbsent(worldId.value) { AtomicLong() }.incrementAndGet()
        override fun onLeaseAcquired(worldId: WorldId) = Unit
    }

    private class SleepingRunner(
        private val perWorldMs: Long,
        private val failOn: Set<WorldId> = emptySet(),
    ) : WorldTickRunner {
        val attempted: MutableSet<WorldId> = ConcurrentHashMap.newKeySet()
        val completed: MutableSet<WorldId> = ConcurrentHashMap.newKeySet()
        private val numbers = ConcurrentHashMap<Long, MutableList<Long>>()

        override fun tickOne(worldId: WorldId, number: Long) {
            attempted += worldId
            numbers.computeIfAbsent(worldId.value) { java.util.Collections.synchronizedList(mutableListOf()) } += number
            Thread.sleep(perWorldMs)
            if (worldId in failOn) error("simulated tick failure for $worldId")
            completed += worldId
        }

        fun numbersFor(worldId: WorldId): List<Long> = numbers[worldId.value]?.toList() ?: emptyList()
    }
}
