package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [RedisCommandQueue] against a real Redis. The cross-pod test
 * is the key one — it sets up two queue instances on the same Redis (the
 * production analog of one MCP-session pod and a different lease-holder
 * pod) and verifies a command submitted on one drains on the other.
 */
@Testcontainers
class RedisCommandQueueIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val worldA = WorldId(1L)
    private val worldB = WorldId(2L)
    private val agentA = AgentId(UUID.randomUUID())
    private val agentB = AgentId(UUID.randomUUID())

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var queue: RedisCommandQueue
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val tick = StubTickCounter()

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        queue = RedisCommandQueue(
            redis = template,
            mapper = mapper,
            tickCounter = tick,
            router = StaticRouter(mapOf(agentA to worldA, agentB to worldB)),
        )
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `submit and drain round-trip a single command`() {
        val command = WorldCommand.MoveAgent(agentA, NodeId(42L))

        val landed = queue.submit(command, appliesAtTick = 5)

        assertEquals(5L, landed)
        val drained = queue.drainFor(worldA, 5)
        assertEquals(listOf(command), drained)
    }

    @Test
    fun `drain a second time returns empty — atomic LRANGE+DEL`() {
        queue.submit(WorldCommand.MoveAgent(agentA, NodeId(1L)), appliesAtTick = 10)

        assertEquals(1, queue.drainFor(worldA, 10).size)
        assertTrue(queue.drainFor(worldA, 10).isEmpty())
    }

    @Test
    fun `submit-side guard clamps appliesAtTick to current world tick + 1`() {
        tick.set(worldA, 100L)
        val command = WorldCommand.MoveAgent(agentA, NodeId(1L))

        val landed = queue.submit(command, appliesAtTick = 5)

        assertEquals(101L, landed, "stale appliesAtTick must be clamped to currentTick + 1")
        assertTrue(queue.drainFor(worldA, 5).isEmpty(), "command must not land in the requested-but-stale tick")
        assertEquals(listOf(command), queue.drainFor(worldA, 101))
    }

    @Test
    fun `submit honours requested tick when ahead of the per-world counter`() {
        tick.set(worldA, 5L)
        val command = WorldCommand.MoveAgent(agentA, NodeId(1L))

        val landed = queue.submit(command, appliesAtTick = 50)

        assertEquals(50L, landed)
        assertEquals(listOf(command), queue.drainFor(worldA, 50))
    }

    @Test
    fun `commands routed to one world are invisible to another world's drain`() {
        queue.submit(WorldCommand.MoveAgent(agentA, NodeId(1L)), appliesAtTick = 7)
        queue.submit(WorldCommand.MoveAgent(agentB, NodeId(9L)), appliesAtTick = 7)

        val drainedA = queue.drainFor(worldA, 7)
        val drainedB = queue.drainFor(worldB, 7)

        assertEquals(1, drainedA.size)
        assertEquals(agentA, drainedA.single().agent)
        assertEquals(1, drainedB.size)
        assertEquals(agentB, drainedB.single().agent)
    }

    @Test
    fun `drain returns commands in submission order`() {
        val first = WorldCommand.MoveAgent(agentA, NodeId(1L))
        val second = WorldCommand.MoveAgent(agentA, NodeId(2L))
        val third = WorldCommand.MoveAgent(agentA, NodeId(3L))
        queue.submit(first, appliesAtTick = 11)
        queue.submit(second, appliesAtTick = 11)
        queue.submit(third, appliesAtTick = 11)

        assertEquals(listOf(first, second, third), queue.drainFor(worldA, 11))
    }

    @Test
    fun `submit on one queue instance, drain on another — cross-pod handoff`() {
        val submitter = RedisCommandQueue(template, mapper, tick, StaticRouter(mapOf(agentA to worldA)))
        val drainer = RedisCommandQueue(template, mapper, tick, StaticRouter(mapOf(agentA to worldA)))
        val command = WorldCommand.AttackTarget(agentA, AgentId(UUID.randomUUID()))

        submitter.submit(command, appliesAtTick = 33)

        assertEquals(listOf(command), drainer.drainFor(worldA, 33))
    }

    @Test
    fun `submit throws when the router cannot resolve a world — invariant violation, no silent loss`() {
        val isolated = RedisCommandQueue(template, mapper, tick, StaticRouter(emptyMap()))
        val orphan = AgentId(UUID.randomUUID())

        val thrown = assertFailsWith<IllegalStateException> {
            isolated.submit(WorldCommand.MoveAgent(orphan, NodeId(1L)), appliesAtTick = 7)
        }

        assertTrue("No worlds configured" in thrown.message.orEmpty())
    }

    private class StaticRouter(private val mapping: Map<AgentId, WorldId>) : AgentWorldRouter {
        override fun routeFor(agent: AgentId): WorldId? = mapping[agent]
    }

    private class StubTickCounter : WorldTickCounter {
        private val ticks = ConcurrentHashMap<Long, Long>()
        fun set(worldId: WorldId, tick: Long) {
            ticks[worldId.value] = tick
        }
        override fun incrementAndGet(worldId: WorldId): Long =
            ticks.compute(worldId.value) { _, v -> (v ?: 0L) + 1 }!!
        override fun currentTick(worldId: WorldId): Long = ticks[worldId.value] ?: 0L
        override fun onLeaseAcquired(worldId: WorldId) {
            ticks.remove(worldId.value)
        }
    }
}
