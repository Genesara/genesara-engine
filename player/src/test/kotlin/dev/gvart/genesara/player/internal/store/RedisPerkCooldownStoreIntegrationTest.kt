package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class RedisPerkCooldownStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val perk = PerkId("SWORD_BLEEDER")

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisPerkCooldownStore
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        agent = AgentId(UUID.randomUUID())
        store = RedisPerkCooldownStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `unarmed perk is ready by default`() {
        assertTrue(store.isReady(agent, perk, tick = 0L))
        assertNull(store.readyAtTick(agent, perk))
    }

    @Test
    fun `arm blocks until the gate tick`() {
        store.arm(agent, perk, untilTick = 10L, currentTick = 0L)

        assertFalse(store.isReady(agent, perk, tick = 9L))
        assertTrue(store.isReady(agent, perk, tick = 10L), "gate tick is inclusive")
        assertTrue(store.isReady(agent, perk, tick = 11L))
        assertEquals(10L, store.readyAtTick(agent, perk))
    }

    @Test
    fun `re-arm overwrites the prior gate tick`() {
        store.arm(agent, perk, untilTick = 5L, currentTick = 0L)
        store.arm(agent, perk, untilTick = 20L, currentTick = 4L)

        assertEquals(20L, store.readyAtTick(agent, perk))
    }

    @Test
    fun `cooldowns are scoped per agent and per perk`() {
        val otherAgent = AgentId(UUID.randomUUID())
        val otherPerk = PerkId("SWORD_PRECISION")

        store.arm(agent, perk, untilTick = 100L, currentTick = 0L)

        assertFalse(store.isReady(agent, perk, tick = 50L))
        assertTrue(store.isReady(otherAgent, perk, tick = 50L))
        assertTrue(store.isReady(agent, otherPerk, tick = 50L))
    }

    @Test
    fun `byAgents batches every armed perk for the requested agents`() {
        val other = AgentId(UUID.randomUUID())
        val absent = AgentId(UUID.randomUUID())
        val perkA = PerkId("SWORD_BLEEDER")
        val perkB = PerkId("SWORD_PRECISION")

        store.arm(agent, perkA, untilTick = 10L, currentTick = 0L)
        store.arm(agent, perkB, untilTick = 25L, currentTick = 0L)
        store.arm(other, perkA, untilTick = 7L, currentTick = 0L)

        val loaded = store.byAgents(setOf(agent, other, absent))

        assertEquals(mapOf(perkA to 10L, perkB to 25L), loaded[agent])
        assertEquals(mapOf(perkA to 7L), loaded[other])
        assertNull(loaded[absent], "agents with no cooldowns are absent from the result map")
    }

    @Test
    fun `byAgents short-circuits on empty set`() {
        store.arm(agent, perk, untilTick = 10L, currentTick = 0L)

        assertEquals(emptyMap(), store.byAgents(emptySet()))
    }

    @Test
    fun `arm rejects a duration past T_persist`() {
        val tooLong = RedisPerkCooldownStore.T_PERSIST_TICKS + 1L
        assertFailsWith<IllegalArgumentException> {
            store.arm(agent, perk, untilTick = tooLong, currentTick = 0L)
        }
    }

    @Test
    fun `arm accepts a duration exactly at T_persist`() {
        store.arm(agent, perk, untilTick = RedisPerkCooldownStore.T_PERSIST_TICKS, currentTick = 0L)
        assertEquals(RedisPerkCooldownStore.T_PERSIST_TICKS, store.readyAtTick(agent, perk))
    }
}
