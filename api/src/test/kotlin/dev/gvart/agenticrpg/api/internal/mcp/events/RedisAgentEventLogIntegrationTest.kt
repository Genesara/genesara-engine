package dev.gvart.agenticrpg.api.internal.mcp.events

import dev.gvart.agenticrpg.player.AgentId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class RedisAgentEventLogIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var template: StringRedisTemplate
    private lateinit var connectionFactory: LettuceConnectionFactory
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val props = EventLogProperties(backlogCap = 5, ttl = Duration.ofMinutes(1))
    private lateinit var log: RedisAgentEventLog

    private val agent = AgentId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        log = RedisAgentEventLog(template, mapper, props)
        // Clear any state from prior tests
        template.connectionFactory!!.connection.serverCommands().flushDb()
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `append stamps monotonic seq and since(0) returns events in FIFO order`() {
        val ticks = (0..2L).toList()
        val events = ticks.map { tick ->
            log.append(agent, "agent.moved", tick, mapper.createObjectNode())
        }

        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })

        val all = log.since(agent, 0)
        assertEquals(ticks, all.map { it.tick })
        assertEquals(listOf(1L, 2L, 3L), all.map { it.seq })
    }

    @Test
    fun `since is non-destructive and supports cursor-based reads`() {
        repeat(3) { i ->
            log.append(agent, "agent.moved", i.toLong(), mapper.createObjectNode())
        }

        // First read with cursor=0 → all three
        assertEquals(3, log.since(agent, 0).size)
        // Same read again → still all three (non-destructive)
        assertEquals(3, log.since(agent, 0).size)
        // Cursor at seq=1 → events 2 and 3
        val tail = log.since(agent, 1)
        assertEquals(listOf(2L, 3L), tail.map { it.seq })
        // Cursor at the latest seq → empty
        assertTrue(log.since(agent, 3).isEmpty())
    }

    @Test
    fun `backlog cap drops oldest entries beyond the configured size`() {
        repeat(props.backlogCap.toInt() + 3) { i ->
            log.append(agent, "agent.moved", i.toLong(), mapper.createObjectNode())
        }

        val all = log.since(agent, 0)
        assertEquals(props.backlogCap.toInt(), all.size)
        // Cap = 5, appended 8 → seqs 4..8 (oldest three dropped)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), all.map { it.seq })
    }

    @Test
    fun `discard removes the agent's events and resets the seq counter`() {
        val other = AgentId(UUID.randomUUID())
        log.append(agent, "agent.moved", 1L, mapper.createObjectNode())
        log.append(other, "agent.moved", 1L, mapper.createObjectNode())

        log.discard(agent)

        assertTrue(log.since(agent, 0).isEmpty())
        assertEquals(1, log.since(other, 0).size)

        // After discard, seq starts from 1 again for the agent
        val resumed = log.append(agent, "agent.moved", 2L, mapper.createObjectNode())
        assertEquals(1L, resumed.seq)
    }
}
