package dev.gvart.genesara.api.internal.mcp.tools.spawn

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
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

    private val agentId = AgentId(UUID.randomUUID())
    private val ownerId = PlayerId(UUID.randomUUID())
    private val saved = NodeId(7L)
    private val starter = NodeId(11L)
    private val random = NodeId(42L)
    private val raceId = RaceId("human_steppe")

    private val baseAgent = Agent(
        id = agentId,
        owner = ownerId,
        name = "Komar",
        race = raceId,
    )

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 100L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns already_present and submits no command when agent is already spawned`() {
        val query = StubQuery(activePosition = saved)
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

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
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(saved.value, response.at)
        assertEquals(101L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val spawnCmd = assertNotNull(cmd as? WorldCommand.SpawnAgent)
        assertEquals(agentId, spawnCmd.agent)
        assertEquals(saved, spawnCmd.at)
        assertEquals(101L, appliesAt)
        assertEquals(spawnCmd.commandId, response.commandId)
    }

    @Test
    fun `queues spawn at the race-keyed starter node when agent has no history`() {
        val query = StubQuery(
            activePosition = null,
            lastLocation = null,
            starterByRace = mapOf(raceId to starter),
            randomNode = random,
        )
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(starter.value, response.at)
        val cmd = gateway.submissions.single().first as WorldCommand.SpawnAgent
        assertEquals(starter, cmd.at)
    }

    @Test
    fun `falls back to a random spawnable node when no starter is configured for the race`() {
        val query = StubQuery(
            activePosition = null,
            lastLocation = null,
            starterByRace = emptyMap(),
            randomNode = random,
        )
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(random.value, response.at)
        val cmd = gateway.submissions.single().first as WorldCommand.SpawnAgent
        assertEquals(random, cmd.at)
    }

    @Test
    fun `falls back to a random spawnable node when the agent is unknown to the registry`() {
        val query = StubQuery(activePosition = null, lastLocation = null, randomNode = random)
        val tool = SpawnTool(gateway, query, StubRegistry(null), tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals("queued", response.kind)
        assertEquals(random.value, response.at)
    }

    @Test
    fun `errors when the world has no spawnable nodes`() {
        val query = StubQuery(activePosition = null, lastLocation = null, randomNode = null)
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

        assertThrows<IllegalStateException> { tool.invoke(SpawnRequest(), toolContext) }
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val query = StubQuery(activePosition = saved)
        val tool = SpawnTool(gateway, query, StubRegistry(baseAgent), tickClock, activity)

        val cutoff = clock.instant().plusSeconds(60)
        assertTrue(agentId !in activity.staleAgents(cutoff))

        tool.invoke(SpawnRequest(), toolContext)

        val past = clock.instant().minusSeconds(60)
        assertTrue(agentId !in activity.staleAgents(past))
        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long) {
            submissions += command to appliesAtTick
        }
    }

    private class StubRegistry(private val agent: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agent
        override fun listForOwner(owner: PlayerId): List<Agent> = listOfNotNull(agent)
    }

    private class StubQuery(
        private val activePosition: NodeId? = null,
        private val lastLocation: NodeId? = null,
        private val starterByRace: Map<RaceId, NodeId> = emptyMap(),
        private val randomNode: NodeId? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = activePosition
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = randomNode
        override fun starterNodeFor(race: RaceId): NodeId? = starterByRace[race]
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
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
