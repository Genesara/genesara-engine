package dev.gvart.agenticrpg.api.internal.mcp.tools.spawn

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.WorldQueryGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val saved = NodeId(7L)
    private val random = NodeId(42L)

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 100L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agent)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `returns already_present and submits no command when agent is already spawned`() {
        val query = StubQuery(activePosition = saved)
        val tool = SpawnTool(gateway, query, tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("already_present", response.kind)
        assertEquals(saved.value, response.at)
        assertNull(response.commandId)
        assertNull(response.appliesAtTick)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `queues spawn at the agent's last known location when not currently active`() {
        val query = StubQuery(activePosition = null, lastLocation = saved)
        val tool = SpawnTool(gateway, query, tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(saved.value, response.at)
        assertEquals(101L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val spawnCmd = assertNotNull(cmd as? WorldCommand.SpawnAgent)
        assertEquals(agent, spawnCmd.agent)
        assertEquals(saved, spawnCmd.at)
        assertEquals(101L, appliesAt)
        assertEquals(spawnCmd.commandId, response.commandId)
    }

    @Test
    fun `falls back to a random spawnable node when agent has no history`() {
        val query = StubQuery(activePosition = null, lastLocation = null, randomNode = random)
        val tool = SpawnTool(gateway, query, tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(random.value, response.at)
        val cmd = gateway.submissions.single().first as WorldCommand.SpawnAgent
        assertEquals(random, cmd.at)
    }

    @Test
    fun `errors when the world has no spawnable nodes`() {
        val query = StubQuery(activePosition = null, lastLocation = null, randomNode = null)
        val tool = SpawnTool(gateway, query, tickClock, activity)

        assertThrows<IllegalStateException> {
            tool.invoke(SpawnRequest(), toolContext)
        }
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val query = StubQuery(activePosition = saved)
        val tool = SpawnTool(gateway, query, tickClock, activity)

        // Before invoke: registry has no record → agent treated as stale relative to the future.
        val cutoff = clock.instant().plusSeconds(60)
        assertTrue(agent !in activity.staleAgents(cutoff))

        tool.invoke(SpawnRequest(), toolContext)

        // After invoke: registry is up to date — agent shows as recent vs. an earlier cutoff.
        val past = clock.instant().minusSeconds(60)
        assertTrue(agent !in activity.staleAgents(past))
        // ...but stale relative to a future cutoff (sanity: registry actually recorded a touch).
        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long) {
            submissions += command to appliesAtTick
        }
    }

    private class StubQuery(
        private val activePosition: NodeId? = null,
        private val lastLocation: NodeId? = null,
        private val randomNode: NodeId? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = activePosition
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = randomNode
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
