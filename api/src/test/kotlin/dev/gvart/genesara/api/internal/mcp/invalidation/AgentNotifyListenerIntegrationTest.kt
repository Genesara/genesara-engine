package dev.gvart.genesara.api.internal.mcp.invalidation

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class AgentNotifyListenerIntegrationTest {

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
    fun `AgentNotify on pod A fires notifyResourcesUpdated on pod B's MCP server`() {
        val publisher = newPublisher()
        val mcp: McpSyncServer = mock(McpSyncServer::class.java)
        AgentNotifyListener(newContainer(), mapper, mcp).subscribe()

        val agent = AgentId(UUID.randomUUID())
        publisher.publish(InvalidationMessage.AgentNotify(agent))

        val captor = ArgumentCaptor.forClass(ResourcesUpdatedNotification::class.java)
        verify(mcp, timeout(5_000)).notifyResourcesUpdated(captor.capture())
        assertEquals("agent://${agent.id}/events", captor.value.uri())
    }

    @Test
    fun `WorldConfigInvalidate on the same channel does NOT trigger MCP notify`() {
        val publisher = newPublisher()
        val mcp: McpSyncServer = mock(McpSyncServer::class.java)
        val container = newContainer()
        AgentNotifyListener(container, mapper, mcp).subscribe()
        val sentinel = subscribeSentinel(container)

        publisher.publish(InvalidationMessage.WorldConfigInvalidate(WorldId(1L)))

        assertTrue(sentinel.await(5, TimeUnit.SECONDS), "sentinel didn't arrive — pub/sub never delivered")
        verifyNoInteractions(mcp)
    }

    private fun newPublisher(): InvalidationBus {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        resources += AutoCloseable { cf.destroy() }
        val template = StringRedisTemplate(cf)
        return object : InvalidationBus {
            override fun publish(message: InvalidationMessage) {
                template.convertAndSend(InvalidationBus.CHANNEL, mapper.writeValueAsString(message))
            }
        }
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

    private fun subscribeSentinel(container: RedisMessageListenerContainer): CountDownLatch {
        val latch = CountDownLatch(1)
        container.addMessageListener(
            MessageListener { _, _ -> latch.countDown() },
            ChannelTopic(InvalidationBus.CHANNEL),
        )
        return latch
    }
}
