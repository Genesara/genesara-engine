package dev.gvart.genesara.api.internal.rest.admin.feed

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.CoreEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class AdminFeedSseIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val props = AdminFeedProperties(
        backlogCap = 5000,
        ttl = Duration.ofMinutes(1),
        maxConnectionsPerToken = 16,
    )
    private val resources = mutableListOf<AutoCloseable>()
    private lateinit var template: StringRedisTemplate
    private lateinit var container: RedisMessageListenerContainer
    private lateinit var log: RedisAdminFeedLog
    private lateinit var broker: AdminFeedSseBroker
    private lateinit var dispatcher: AdminFeedDispatcher

    @BeforeEach
    fun setUp() {
        template = newTemplate()
        template.connectionFactory!!.connection.serverCommands().flushDb()
        container = newContainer()
        log = RedisAdminFeedLog(template, mapper, props)
        broker = AdminFeedSseBroker(log, container, props)
        broker.subscribe()
        dispatcher = AdminFeedDispatcher(log, template, mapper)
    }

    @AfterEach
    fun tearDown() {
        resources.reversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    @Test
    fun `spawn then two moves stream as agent_spawned plus two agent_moved with monotonic seq`() {
        val agent = AgentId(UUID.randomUUID())
        val emitter = subscribe(filter = AdminFeedFilter.NONE)

        dispatcher.onWorld(CoreEvent.AgentSpawned(agent, NodeId(1L), tick = 1L, causedBy = UUID.randomUUID()))
        dispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), staminaSpent = 1, tick = 2L, causedBy = UUID.randomUUID()))
        dispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(2L), NodeId(3L), staminaSpent = 1, tick = 3L, causedBy = UUID.randomUUID()))

        emitter.awaitDataSize(3)

        val captured = emitter.captured()
        assertEquals(listOf(1L, 2L, 3L), captured.map { it.seq })
        assertEquals(listOf("agent.spawned", "agent.moved", "agent.moved"), captured.map { it.type })
        assertEquals(agent.id, captured.first().agent)
    }

    @Test
    fun `server-side filter by type drops non-matching events`() {
        val agent = AgentId(UUID.randomUUID())
        val emitter = subscribe(filter = AdminFeedFilter(types = setOf("agent.moved")))

        dispatcher.onWorld(CoreEvent.AgentSpawned(agent, NodeId(1L), tick = 1L, causedBy = UUID.randomUUID()))
        dispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), staminaSpent = 1, tick = 2L, causedBy = UUID.randomUUID()))

        emitter.awaitDataSize(1)
        assertEquals(listOf("agent.moved"), emitter.captured().map { it.type })
    }

    @Test
    fun `server-side filter by agent drops events from other agents`() {
        val keep = AgentId(UUID.randomUUID())
        val drop = AgentId(UUID.randomUUID())
        val emitter = subscribe(filter = AdminFeedFilter(agent = keep.id))

        dispatcher.onWorld(CoreEvent.AgentSpawned(drop, NodeId(1L), tick = 1L, causedBy = UUID.randomUUID()))
        dispatcher.onWorld(CoreEvent.AgentSpawned(keep, NodeId(2L), tick = 2L, causedBy = UUID.randomUUID()))

        emitter.awaitDataSize(1)
        assertEquals(keep.id, emitter.captured().single().agent)
    }

    @Test
    fun `range endpoint returns events whose seq falls in from to inclusive`() {
        val agent = AgentId(UUID.randomUUID())
        repeat(5) {
            dispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(it.toLong()), NodeId(it + 1L), staminaSpent = 1, tick = it.toLong(), causedBy = UUID.randomUUID()))
        }

        val slice = log.range(from = 2L, to = 4L, filter = AdminFeedFilter.NONE)
        assertEquals(listOf(2L, 3L, 4L), slice.map { it.seq })
    }

    @Test
    fun `pressure run of 500 events lands on one subscriber with no drops and monotonic seq`() {
        val agent = AgentId(UUID.randomUUID())
        val emitter = subscribe(filter = AdminFeedFilter.NONE)

        val totalEvents = 500
        for (i in 1..totalEvents) {
            dispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(0L), NodeId(i.toLong()), staminaSpent = 1, tick = i.toLong(), causedBy = UUID.randomUUID()))
            if (i % 100 == 0) Thread.sleep(10)
        }

        emitter.awaitDataSize(totalEvents, timeoutMs = 30_000L)

        val seqs = emitter.captured().map { it.seq }
        assertEquals((1L..totalEvents.toLong()).toList(), seqs)
    }

    @Test
    fun `backlog cap evicts the oldest entries beyond the configured size`() {
        val tightProps = props.copy(backlogCap = 5)
        val tightLog = RedisAdminFeedLog(template, mapper, tightProps)
        val tightDispatcher = AdminFeedDispatcher(tightLog, template, mapper)
        val agent = AgentId(UUID.randomUUID())

        repeat(8) {
            tightDispatcher.onWorld(CoreEvent.AgentMoved(agent, NodeId(0L), NodeId(it.toLong()), staminaSpent = 1, tick = it.toLong(), causedBy = UUID.randomUUID()))
        }

        val all = tightLog.since(after = 0L, filter = AdminFeedFilter.NONE)
        assertEquals(5, all.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), all.map { it.seq })
    }

    private fun subscribe(filter: AdminFeedFilter): RecordingSseEmitter {
        val emitter = RecordingSseEmitter()
        broker.registerEmitter(token = "test-token", emitter = emitter, afterSeq = 0L, filter = filter)
        return emitter
    }

    private fun newTemplate(): StringRedisTemplate {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        resources += AutoCloseable { cf.destroy() }
        return StringRedisTemplate(cf)
    }

    private fun newContainer(): RedisMessageListenerContainer {
        val cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        resources += AutoCloseable { cf.destroy() }
        val c = RedisMessageListenerContainer().apply {
            setConnectionFactory(cf)
            afterPropertiesSet()
            start()
        }
        resources += AutoCloseable { c.stop() }
        return c
    }

    private class RecordingSseEmitter : SseEmitter(60_000L) {
        private val received = ConcurrentLinkedQueue<AdminFeedEvent>()

        override fun send(builder: SseEventBuilder) {
            builder.build().forEach { dwm ->
                val value = dwm.data
                if (value is AdminFeedEvent) received += value
            }
        }

        fun captured(): List<AdminFeedEvent> = received.toList()

        fun awaitDataSize(target: Int, timeoutMs: Long = 5_000L) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && received.size < target) {
                Thread.sleep(10)
            }
            assertTrue(received.size >= target, "expected $target events, only saw ${received.size}")
        }
    }
}
