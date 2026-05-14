package dev.gvart.genesara.world.internal.invalidation

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache
import dev.gvart.genesara.world.internal.tick.lease.LeasedWorlds
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class WorldConfigInvalidateListenerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val resources = mutableListOf<AutoCloseable>()

    @BeforeEach
    fun flushRedis() {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        StringRedisTemplate(cf).connectionFactory!!.connection.serverCommands().flushDb()
        cf.destroy()
    }

    @AfterEach
    fun tearDown() {
        resources.reversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    @Test
    fun `reload fires when this pod holds at least one lease`() {
        val publisher = newPublisher()
        val reloadCount = AtomicInteger(0)
        val reloadFired = CountDownLatch(1)
        val container = newContainer()
        WorldConfigInvalidateListener(
            container,
            mapper,
            StaticLeasedWorlds(listOf(WorldId(11L))),
            countingStaticConfig(reloadCount, reloadFired),
            InMemoryVisionBlockerCache(),
        ).subscribe()

        publisher.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(11L)))

        assertTrue(reloadFired.await(5, TimeUnit.SECONDS))
        assertEquals(1, reloadCount.get())
    }

    @Test
    fun `reload still fires for a world this pod does NOT lease, as long as something is leased`() {
        val publisher = newPublisher()
        val reloadCount = AtomicInteger(0)
        val reloadFired = CountDownLatch(1)
        val container = newContainer()
        WorldConfigInvalidateListener(
            container,
            mapper,
            StaticLeasedWorlds(listOf(WorldId(1L))),
            countingStaticConfig(reloadCount, reloadFired),
            InMemoryVisionBlockerCache(),
        ).subscribe()

        publisher.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(99L)))

        assertTrue(reloadFired.await(5, TimeUnit.SECONDS))
        assertEquals(1, reloadCount.get(), "reload is global; any-lease pod must reload on any world invalidation")
    }

    @Test
    fun `reload does NOT fire when this pod holds no leases`() {
        val publisher = newPublisher()
        val reloadCount = AtomicInteger(0)
        val container = newContainer()
        WorldConfigInvalidateListener(
            container,
            mapper,
            StaticLeasedWorlds(emptyList()),
            countingStaticConfig(reloadCount, latch = null),
            InMemoryVisionBlockerCache(),
        ).subscribe()
        val sentinelArrived = subscribeSentinel(container)

        publisher.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(99L)))

        assertTrue(sentinelArrived.await(5, TimeUnit.SECONDS), "sentinel didn't arrive — pub/sub never delivered")
        assertEquals(0, reloadCount.get())
    }

    @Test
    fun `AgentNotify on the same channel is ignored by the world-config listener`() {
        val publisher = newPublisher()
        val reloadCount = AtomicInteger(0)
        val container = newContainer()
        WorldConfigInvalidateListener(
            container,
            mapper,
            StaticLeasedWorlds(listOf(WorldId(7L))),
            countingStaticConfig(reloadCount, latch = null),
            InMemoryVisionBlockerCache(),
        ).subscribe()
        val sentinelArrived = subscribeSentinel(container)

        publisher.publish(InvalidationMessage.AgentNotify(AgentId(UUID.randomUUID())))

        assertTrue(sentinelArrived.await(5, TimeUnit.SECONDS))
        assertEquals(0, reloadCount.get())
    }

    private fun subscribeSentinel(container: RedisMessageListenerContainer): CountDownLatch {
        val latch = CountDownLatch(1)
        container.addMessageListener(
            MessageListener { _, _ -> latch.countDown() },
            ChannelTopic(InvalidationBus.CHANNEL),
        )
        return latch
    }

    private fun newPublisher(): InvalidationBus {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        resources += AutoCloseable { cf.destroy() }
        return RedisInvalidationBus(StringRedisTemplate(cf), mapper)
    }

    private fun newContainer(): RedisMessageListenerContainer {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        resources += AutoCloseable { cf.destroy() }
        val container = RedisMessageListenerContainer().apply {
            setConnectionFactory(cf)
            afterPropertiesSet()
            start()
        }
        resources += AutoCloseable { container.stop() }
        return container
    }

    private fun countingStaticConfig(counter: AtomicInteger, latch: CountDownLatch?): WorldStaticConfig =
        object : WorldStaticConfig(unusedDsl(), mapper) {
            override fun reload() {
                counter.incrementAndGet()
                latch?.countDown()
            }
        }

    private fun unusedDsl(): org.jooq.DSLContext =
        org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES)

    private class StaticLeasedWorlds(private val held: List<WorldId>) : LeasedWorlds {
        override fun held(): List<WorldId> = held
    }
}
