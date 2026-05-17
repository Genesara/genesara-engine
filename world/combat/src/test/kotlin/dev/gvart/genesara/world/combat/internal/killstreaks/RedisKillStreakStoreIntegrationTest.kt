package dev.gvart.genesara.world.combat.internal.killstreaks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class RedisKillStreakStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisKillStreakStore
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        agent = AgentId(UUID.randomUUID())
        store = RedisKillStreakStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `save then byAgents round-trips streak`() {
        val streak = AgentKillStreak(killCount = 3, windowStartTick = 100L)

        store.save(agent, streak)
        val loaded = store.byAgents(setOf(agent))

        assertEquals(streak, loaded[agent])
    }

    @Test
    fun `save EMPTY deletes the row rather than persisting (0, 0)`() {
        store.save(agent, AgentKillStreak(killCount = 2, windowStartTick = 50L))
        store.save(agent, AgentKillStreak.EMPTY)

        assertEquals(emptyMap(), store.byAgents(setOf(agent)))
    }

    @Test
    fun `byAgents skips agents without a row`() {
        val present = agent
        val absent = AgentId(UUID.randomUUID())
        store.save(present, AgentKillStreak(killCount = 1, windowStartTick = 5L))

        val loaded = store.byAgents(setOf(present, absent))

        assertEquals(AgentKillStreak(killCount = 1, windowStartTick = 5L), loaded[present])
        assertNull(loaded[absent])
    }

    @Test
    fun `byAgents short-circuits on empty set`() {
        store.save(agent, AgentKillStreak(killCount = 1, windowStartTick = 5L))

        assertEquals(emptyMap(), store.byAgents(emptySet()))
    }

    @Test
    fun `delete removes the streak`() {
        store.save(agent, AgentKillStreak(killCount = 4, windowStartTick = 10L))
        store.delete(agent)

        assertNull(store.byAgents(setOf(agent))[agent])
    }
}
