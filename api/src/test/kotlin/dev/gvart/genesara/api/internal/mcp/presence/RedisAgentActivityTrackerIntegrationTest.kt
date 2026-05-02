package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentLastActiveStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class RedisAgentActivityTrackerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var template: StringRedisTemplate
    private lateinit var connectionFactory: LettuceConnectionFactory

    private val now = Instant.parse("2026-05-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val agentA = AgentId(UUID.randomUUID())
    private val agentB = AgentId(UUID.randomUUID())
    private val agentC = AgentId(UUID.randomUUID())

    private val coldStore = StubLastActiveStore(
        mapOf(agentC to now.minusSeconds(3600)),
    )

    private lateinit var tracker: RedisAgentActivityTracker

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        tracker = RedisAgentActivityTracker(template, coldStore, clock)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `touch then lastActiveAt round-trips through Redis`() {
        tracker.touch(agentA)

        assertEquals(now, tracker.lastActiveAt(agentA))
    }

    @Test
    fun `lastActiveAt falls back to the cold store when Redis has no entry`() {
        val coldTime = now.minusSeconds(3600)

        assertEquals(coldTime, tracker.lastActiveAt(agentC))
        assertNull(tracker.lastActiveAt(agentB))
    }

    @Test
    fun `lastActiveBatch combines hot Redis entries with cold-store fallbacks`() {
        tracker.touch(agentA)

        val result = tracker.lastActiveBatch(listOf(agentA, agentB, agentC))

        assertEquals(now, result[agentA])
        assertEquals(null, result[agentB])
        assertEquals(now.minusSeconds(3600), result[agentC])
    }

    @Test
    fun `staleAgents returns only entries older than the cutoff`() {
        val staleClockTracker = RedisAgentActivityTracker(
            template,
            coldStore,
            Clock.fixed(now.minusSeconds(120), ZoneOffset.UTC),
        )
        staleClockTracker.touch(agentA)
        tracker.touch(agentB)

        val cutoff = now.minusSeconds(60)
        val stale = tracker.staleAgents(cutoff)

        assertEquals(listOf(agentA), stale)
    }

    @Test
    fun `forget removes the entry from Redis but does not touch the cold store`() {
        tracker.touch(agentA)
        tracker.forget(agentA)

        assertNull(tracker.lastActiveAt(agentA))
    }

    @Test
    fun `snapshot returns every Redis entry mapped back to AgentId and Instant`() {
        tracker.touch(agentA)
        tracker.touch(agentB)

        val snap = tracker.snapshot()

        assertTrue(agentA in snap)
        assertTrue(agentB in snap)
        assertEquals(now, snap[agentA])
        assertEquals(now, snap[agentB])
    }

    private class StubLastActiveStore(private val rows: Map<AgentId, Instant>) : AgentLastActiveStore {
        override fun findLastActive(agentId: AgentId): Instant? = rows[agentId]
        override fun findLastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> =
            rows.filterKeys { it in ids }
        override fun saveLastActive(updates: Map<AgentId, Instant>) = error("not used")
    }
}
