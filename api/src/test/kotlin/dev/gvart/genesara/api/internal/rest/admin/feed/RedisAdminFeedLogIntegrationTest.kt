package dev.gvart.genesara.api.internal.rest.admin.feed

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class RedisAdminFeedLogIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val props = AdminFeedProperties(
        backlogCap = 5,
        ttl = Duration.ofMinutes(1),
        maxConnectionsPerToken = 16,
    )
    private lateinit var template: StringRedisTemplate
    private lateinit var cf: LettuceConnectionFactory
    private lateinit var log: RedisAdminFeedLog

    @BeforeEach
    fun setUp() {
        cf = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply { afterPropertiesSet() }
        template = StringRedisTemplate(cf)
        log = RedisAdminFeedLog(template, mapper, props)
        template.connectionFactory!!.connection.serverCommands().flushDb()
    }

    @AfterEach
    fun tearDown() {
        cf.destroy()
    }

    @Test
    fun `append stamps monotonic seq across events`() {
        val first = log.append("agent.moved", tick = 1L, agent = null, node = 1L, payload = mapper.createObjectNode())
        val second = log.append("agent.moved", tick = 2L, agent = null, node = 2L, payload = mapper.createObjectNode())
        val third = log.append("agent.moved", tick = 3L, agent = null, node = 3L, payload = mapper.createObjectNode())

        assertEquals(listOf(1L, 2L, 3L), listOf(first.seq, second.seq, third.seq))
    }

    @Test
    fun `since respects after cursor and filter`() {
        val agentA = UUID.randomUUID()
        val agentB = UUID.randomUUID()
        log.append("agent.moved", tick = 1L, agent = agentA, node = 1L, payload = mapper.createObjectNode())
        log.append("agent.spawned", tick = 2L, agent = agentB, node = 2L, payload = mapper.createObjectNode())
        log.append("agent.moved", tick = 3L, agent = agentA, node = 3L, payload = mapper.createObjectNode())

        val onlyMoves = log.since(after = 0L, filter = AdminFeedFilter(types = setOf("agent.moved")))
        assertEquals(listOf(1L, 3L), onlyMoves.map { it.seq })

        val afterCursor = log.since(after = 2L, filter = AdminFeedFilter.NONE)
        assertEquals(listOf(3L), afterCursor.map { it.seq })

        val onlyAgentB = log.since(after = 0L, filter = AdminFeedFilter(agent = agentB))
        assertEquals(listOf(2L), onlyAgentB.map { it.seq })
    }

    @Test
    fun `range returns the seq window inclusive on both ends`() {
        repeat(6) { log.append("agent.moved", tick = it.toLong(), agent = null, node = null, payload = mapper.createObjectNode()) }

        val slice = log.range(from = 2L, to = 4L, filter = AdminFeedFilter.NONE)
        assertEquals(listOf(2L, 3L, 4L), slice.map { it.seq })
    }

    @Test
    fun `backlog cap evicts oldest events past the configured size`() {
        repeat(props.backlogCap.toInt() + 3) { i ->
            log.append("agent.moved", tick = i.toLong(), agent = null, node = null, payload = mapper.createObjectNode())
        }

        val all = log.since(after = 0L, filter = AdminFeedFilter.NONE)
        assertEquals(props.backlogCap.toInt(), all.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), all.map { it.seq })
    }

    @Test
    fun `seq cursor at the latest entry returns empty since`() {
        val last = log.append("agent.moved", tick = 0L, agent = null, node = null, payload = mapper.createObjectNode())

        assertTrue(log.since(after = last.seq, filter = AdminFeedFilter.NONE).isEmpty())
    }

    @Test
    fun `payload survives a roundtrip through redis`() {
        val agent = UUID.randomUUID()
        val payload = mapper.createObjectNode().apply { put("flag", true).put("counter", 7) }
        log.append("agent.moved", tick = 1L, agent = agent, node = 42L, payload = payload)

        val read = log.since(after = 0L, filter = AdminFeedFilter.NONE).single()
        assertEquals("agent.moved", read.type)
        assertEquals(agent, read.agent)
        assertEquals(42L, read.node)
        assertEquals(true, read.payload.get("flag").asBoolean())
        assertEquals(7, read.payload.get("counter").asInt())
    }

    @Test
    fun `event without an agent or node has null facets`() {
        val event = log.append("ambient.tick", tick = 0L, agent = null, node = null, payload = mapper.createObjectNode())
        val read = log.since(after = 0L, filter = AdminFeedFilter.NONE).single()
        assertEquals(event.seq, read.seq)
        assertNull(read.agent)
        assertNull(read.node)
    }
}
