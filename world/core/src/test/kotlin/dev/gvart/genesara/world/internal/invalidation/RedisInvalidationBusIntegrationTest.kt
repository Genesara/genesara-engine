package dev.gvart.genesara.world.internal.invalidation

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.Message
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Testcontainers
class RedisInvalidationBusIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val pods = mutableListOf<Pod>()

    @BeforeEach
    fun flushRedis() {
        val tmp = newPod().also { pods += it }
        tmp.template.connectionFactory!!.connection.serverCommands().flushDb()
    }

    @AfterEach
    fun tearDown() {
        pods.forEach { it.close() }
        pods.clear()
    }

    @Test
    fun `WorldConfigInvalidate published on pod A is delivered to pod B's listener`() {
        val podA = newPod().also { pods += it }
        val podB = newPod().also { pods += it }
        val received = LinkedBlockingQueue<InvalidationMessage>()
        podB.subscribe { received.put(it) }

        podA.bus.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(42L)))

        val msg = received.poll(5, TimeUnit.SECONDS)
        assertEquals(InvalidationMessage.WorldConfigInvalidate(WorldId(42L)), msg)
    }

    @Test
    fun `AgentNotify published on pod A is delivered to pod B's listener`() {
        val podA = newPod().also { pods += it }
        val podB = newPod().also { pods += it }
        val received = LinkedBlockingQueue<InvalidationMessage>()
        podB.subscribe { received.put(it) }

        val agent = AgentId(UUID.randomUUID())
        podA.bus.publish(InvalidationMessage.AgentNotify(agent))

        val msg = received.poll(5, TimeUnit.SECONDS)
        assertEquals(InvalidationMessage.AgentNotify(agent), msg)
    }

    @Test
    fun `mixed message types route to the correct deserialized subtype on the receiver`() {
        val podA = newPod().also { pods += it }
        val podB = newPod().also { pods += it }
        val received = LinkedBlockingQueue<InvalidationMessage>()
        podB.subscribe { received.put(it) }

        val agent = AgentId(UUID.randomUUID())
        podA.bus.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(7L)))
        podA.bus.publish(InvalidationMessage.AgentNotify(agent))

        val first = received.poll(5, TimeUnit.SECONDS)
        val second = received.poll(5, TimeUnit.SECONDS)
        val both = setOf(first, second)
        assertEquals(
            setOf<InvalidationMessage>(
                InvalidationMessage.WorldConfigInvalidate(WorldId(7L)),
                InvalidationMessage.AgentNotify(agent),
            ),
            both,
        )
    }

    @Test
    fun `pod that does not subscribe receives nothing — no implicit broadcast`() {
        val podA = newPod().also { pods += it }
        val podB = newPod().also { pods += it }

        podA.bus.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(1L)))

        assertEquals(0, podB.subscriberCount())
    }

    private fun newPod(): Pod {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        val template = StringRedisTemplate(cf)
        val container = RedisMessageListenerContainer().apply {
            setConnectionFactory(cf)
            afterPropertiesSet()
            start()
        }
        val bus = RedisInvalidationBus(template, mapper)
        return Pod(cf, template, container, bus, mapper)
    }

    private class Pod(
        private val cf: LettuceConnectionFactory,
        val template: StringRedisTemplate,
        private val container: RedisMessageListenerContainer,
        val bus: RedisInvalidationBus,
        private val mapper: tools.jackson.databind.ObjectMapper,
    ) {
        private var subscribers = 0

        fun subscribe(handler: (InvalidationMessage) -> Unit) {
            val listener = MessageListener { message: Message, _ ->
                val parsed = mapper.readValue(message.body, InvalidationMessage::class.java)
                handler(parsed)
            }
            container.addMessageListener(listener, ChannelTopic(InvalidationBus.CHANNEL))
            subscribers += 1
        }

        fun subscriberCount(): Int = subscribers

        fun close() {
            container.stop()
            cf.destroy()
        }
    }
}
