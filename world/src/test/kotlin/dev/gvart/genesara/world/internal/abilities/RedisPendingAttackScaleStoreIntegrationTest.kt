package dev.gvart.genesara.world.internal.abilities

import dev.gvart.genesara.player.AgentId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Testcontainers
class RedisPendingAttackScaleStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisPendingAttackScaleStore
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        agent = AgentId(UUID.randomUUID())
        store = RedisPendingAttackScaleStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `stage then consume returns the multiplier`() {
        store.stage(agent, multiplierPct = 150, ttlSeconds = 60L)

        assertEquals(150, store.consume(agent))
    }

    @Test
    fun `consume returns null when nothing is staged`() {
        assertNull(store.consume(agent))
    }

    @Test
    fun `consume is single-shot — second caller sees null`() {
        store.stage(agent, multiplierPct = 200, ttlSeconds = 60L)

        assertEquals(200, store.consume(agent))
        assertNull(store.consume(agent))
    }

    @Test
    fun `TTL expires the key — late attack sees no buff`() {
        store.stage(agent, multiplierPct = 150, ttlSeconds = 1L)

        Thread.sleep(1500L)

        assertNull(store.consume(agent), "expired pending scale closes the active-ability-buff-expiry hole")
    }

    @Test
    fun `byAgents batches every staged scale for the requested agents`() {
        val other = AgentId(UUID.randomUUID())
        val absent = AgentId(UUID.randomUUID())
        store.stage(agent, multiplierPct = 150, ttlSeconds = 60L)
        store.stage(other, multiplierPct = 175, ttlSeconds = 60L)

        val loaded = store.byAgents(setOf(agent, other, absent))

        assertEquals(150, loaded[agent])
        assertEquals(175, loaded[other])
        assertNull(loaded[absent])
    }

    @Test
    fun `byAgents short-circuits on empty set`() {
        store.stage(agent, multiplierPct = 150, ttlSeconds = 60L)

        assertEquals(emptyMap(), store.byAgents(emptySet()))
    }

    @Test
    fun `stage rejects zero or negative TTL`() {
        assertFailsWith<IllegalArgumentException> {
            store.stage(agent, multiplierPct = 150, ttlSeconds = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            store.stage(agent, multiplierPct = 150, ttlSeconds = -1L)
        }
    }
}
