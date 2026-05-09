package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.WorldTickCounter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class LeaseManagerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisWorldLeaseStore

    private val worldA = WorldId(1L)
    private val worldB = WorldId(2L)
    private val worldC = WorldId(3L)

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
    fun `discover acquires every unleased world up to maxPerPod`() {
        val counter = RecordingCounter()
        val manager = newManager(maxPerPod = 10, counter = counter, worlds = listOf(worldA, worldB, worldC))

        manager.discover()

        assertEquals(setOf(worldA, worldB, worldC), manager.held().toSet())
        assertEquals(setOf(worldA, worldB, worldC), counter.acquired)
    }

    @Test
    fun `discover honours maxPerPod cap and stops adding once full`() {
        val manager = newManager(maxPerPod = 1, worlds = listOf(worldA, worldB, worldC))

        manager.discover()
        assertEquals(1, manager.held().size, "only one lease should be acquired at cap=1")

        // Capacity already saturated — a second pass adds nothing.
        manager.discover()
        assertEquals(1, manager.held().size)
    }

    @Test
    fun `discover skips worlds already leased by another pod`() {
        val pod1 = newManager(podId = "pod-1", worlds = listOf(worldA, worldB))
        val pod2 = newManager(podId = "pod-2", worlds = listOf(worldA, worldB))

        pod1.discover()
        val pod1Held = pod1.held()

        pod2.discover()
        val pod2Held = pod2.held()

        // Each world is owned by exactly one pod.
        assertEquals(setOf(worldA, worldB), (pod1Held + pod2Held).toSet())
        assertTrue(pod1Held.intersect(pod2Held.toSet()).isEmpty(), "no overlap: $pod1Held vs $pod2Held")
    }

    @Test
    fun `requireHeldAndRenew succeeds for the holder and refreshes the TTL`() {
        val manager = newManager(podId = "pod-x", worlds = listOf(worldA))
        manager.discover()

        manager.requireHeldAndRenew(worldA, tick = 7L)

        // Still held after the fence call.
        assertEquals(listOf(worldA), manager.held())
        assertEquals("pod-x", template.opsForValue().get("world:${worldA.value}:lease"))
    }

    @Test
    fun `requireHeldAndRenew throws LeaseLost when the key was deleted out from under us`() {
        val manager = newManager(podId = "pod-x", worlds = listOf(worldA))
        manager.discover()
        // Simulate lease expiry mid-tick — another connection wipes the key.
        template.delete("world:${worldA.value}:lease")

        assertThrows<LeaseLost> {
            manager.requireHeldAndRenew(worldA, tick = 9L)
        }

        assertTrue(manager.held().isEmpty(), "lease must be dropped from owned set on lost-fence")
    }

    @Test
    fun `releaseAll on shutdown frees every held lease so failover takes one cycle`() {
        val manager = newManager(podId = "pod-shutdown", worlds = listOf(worldA, worldB, worldC))
        manager.discover()
        assertEquals(3, manager.held().size)

        manager.releaseAll()

        assertTrue(manager.held().isEmpty())
        assertNull(template.opsForValue().get("world:${worldA.value}:lease"))
        assertNull(template.opsForValue().get("world:${worldB.value}:lease"))
        assertNull(template.opsForValue().get("world:${worldC.value}:lease"))
    }

    @Test
    fun `requireHeldAndRenew throws LeaseLost after another pod has reacquired the world`() {
        val pod1 = newManager(podId = "pod-1", worlds = listOf(worldA))
        pod1.discover()

        // Simulate TTL expiry plus pod-2 stealing the world.
        template.delete("world:${worldA.value}:lease")
        store.tryAcquire(worldA, "pod-2")

        assertThrows<LeaseLost> {
            pod1.requireHeldAndRenew(worldA, tick = 100L)
        }

        // The fenced GET ensures pod-1's renewal didn't trample pod-2's value.
        assertEquals("pod-2", template.opsForValue().get("world:${worldA.value}:lease"))
        assertTrue(pod1.held().isEmpty())
    }

    @Test
    fun `releaseAll only deletes leases the pod still owns — does not steal someone else's`() {
        val pod1 = newManager(podId = "pod-1", worlds = listOf(worldA))
        pod1.discover()

        // pod-2 takes worldA's key under its own value (e.g. pod-1 dropped offline,
        // worldA expired, pod-2 reacquired). pod-1's stale shutdown must not delete it.
        template.delete("world:${worldA.value}:lease")
        store.tryAcquire(worldA, "pod-2")

        pod1.releaseAll()

        assertEquals("pod-2", template.opsForValue().get("world:${worldA.value}:lease"))
    }

    private fun newManager(
        podId: String = UUID.randomUUID().toString(),
        maxPerPod: Int = 1024,
        counter: WorldTickCounter = RecordingCounter(),
        worlds: List<WorldId>,
    ): LeaseManager =
        LeaseManager(
            store = store,
            knownWorlds = StubKnownWorlds(worlds),
            pod = StubPodIdentity(podId),
            counter = counter,
            maxPerPod = maxPerPod,
        )

    private class StubKnownWorlds(private val ids: List<WorldId>) : KnownWorlds {
        override fun all(): List<WorldId> = ids
    }

    private class RecordingCounter : WorldTickCounter {
        val acquired: MutableSet<WorldId> = ConcurrentHashMap.newKeySet()
        override fun incrementAndGet(worldId: WorldId): Long = error("not used in lease tests")
        override fun onLeaseAcquired(worldId: WorldId) {
            acquired += worldId
        }
    }
}

private fun StubPodIdentity(value: String): PodIdentity =
    PodIdentity(configured = value)
