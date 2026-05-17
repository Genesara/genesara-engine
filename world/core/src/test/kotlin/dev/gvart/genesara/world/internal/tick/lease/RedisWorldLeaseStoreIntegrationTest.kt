package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class RedisWorldLeaseStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisWorldLeaseStore

    private val world = WorldId(42L)
    private val podA = "pod-a"
    private val podB = "pod-b"

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
    fun `tryAcquire is mutually exclusive — only the first pod wins`() {
        assertTrue(store.tryAcquire(world, podA))
        assertFalse(store.tryAcquire(world, podB), "second acquire on a held lease must fail")
        assertEquals(podA, leaseValue(world))
    }

    @Test
    fun `tryAcquire sets a TTL so an unreleased lease eventually frees up`() {
        store.tryAcquire(world, podA)
        val ttl = template.getExpire(leaseKey(world), TimeUnit.SECONDS)

        assertTrue(ttl in 1..10, "TTL should be the configured 10s window, got $ttl")
    }

    @Test
    fun `verifyAndRenew renews the holder's TTL and rejects everyone else`() {
        store.tryAcquire(world, podA)
        // Ages the TTL so the renewal is observable.
        template.expire(leaseKey(world), Duration.ofSeconds(2))

        assertTrue(store.verifyAndRenew(world, podA))
        val renewed = template.getExpire(leaseKey(world), TimeUnit.SECONDS)
        assertTrue(renewed in 9..10, "TTL must be reset to ~10s, got $renewed")

        assertFalse(store.verifyAndRenew(world, podB), "non-holder must not be able to renew")
    }

    @Test
    fun `verifyAndRenew on a missing key returns false without recreating it`() {
        assertFalse(store.verifyAndRenew(world, podA))
        assertNull(leaseValue(world), "fence must not synthesize a lease")
    }

    @Test
    fun `construction rejects a TTL shorter than 2x the tick interval`() {
        val ex = assertThrows<IllegalArgumentException> {
            RedisWorldLeaseStore(
                template,
                ttl = Duration.ofSeconds(5),
                tickInterval = Duration.ofSeconds(5),
            )
        }
        assertTrue(ex.message!!.contains("must be at least 2"))
    }

    @Test
    fun `release deletes only when the caller still holds the lease`() {
        store.tryAcquire(world, podA)

        assertFalse(store.release(world, podB), "non-holder release must be a no-op")
        assertEquals(podA, leaseValue(world), "lease key still belongs to podA")

        assertTrue(store.release(world, podA))
        assertNull(leaseValue(world))
    }

    private fun leaseKey(worldId: WorldId) = "world:${worldId.value}:lease"
    private fun leaseValue(worldId: WorldId) = template.opsForValue().get(leaseKey(worldId))
}
