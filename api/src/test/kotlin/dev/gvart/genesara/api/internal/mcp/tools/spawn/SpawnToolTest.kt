package dev.gvart.genesara.api.internal.mcp.tools.spawn

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.commands.WorldCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext

class SpawnToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 100L)
    private val toolContext = ToolContext(emptyMap())

    private val raceId = RaceId("human_commoner")
    private val agent = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "Komar",
        race = raceId,
        attributes = AgentAttributes(),
    )
    private val starterNode = NodeId(42L)
    private val profile = AgentProfile(id = agentId, maxHp = 80, maxStamina = 50, maxMana = 5)

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a SpawnAgent command at the next tick and returns the ack`() {
        val tool = spawnTool()

        val response = tool.invoke(toolContext)

        assertEquals(101L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val spawn = assertNotNull(cmd as? CoreCommand.SpawnAgent)
        assertEquals(agentId, spawn.agent)
        assertEquals(101L, appliesAt)
        assertEquals(spawn.commandId, response.commandId)
    }

    @Test
    fun `returns existing location and body hp for a returning agent`() {
        val existingNode = NodeId(99L)
        val body = BodyView(hp = 75, maxHp = 80, stamina = 30, maxStamina = 50, mana = 0, maxMana = 5,
            hunger = 100, maxHunger = 100, thirst = 100, maxThirst = 100, sleep = 100, maxSleep = 100)
        val tool = spawnTool(lastLocation = existingNode, body = body)

        val response = tool.invoke(toolContext)

        assertEquals(99L, response.initialLocation)
        assertEquals(75, response.initialHp)
    }

    @Test
    fun `returns starter-node location and profile max hp for a first-time agent`() {
        val tool = spawnTool(starterNode = starterNode)

        val response = tool.invoke(toolContext)

        assertEquals(42L, response.initialLocation)
        assertEquals(80, response.initialHp)
    }

    @Test
    fun `returns null location and null hp when no node and no profile exist`() {
        val tool = spawnTool(hasAgent = false)

        val response = tool.invoke(toolContext)

        assertNull(response.initialLocation)
        assertNull(response.initialHp)
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = spawnTool()

        assertTrue(agentId !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private fun spawnTool(
        lastLocation: NodeId? = null,
        body: BodyView? = null,
        starterNode: NodeId? = null,
        hasAgent: Boolean = true,
    ) = SpawnTool(
        world = gateway,
        worldQuery = StubQuery(lastLocation = lastLocation, body = body, starterNode = starterNode),
        engine = tickClock,
        activity = activity,
        agents = StubAgentRegistry(if (hasAgent) agent else null),
        profiles = StubProfileLookup(if (hasAgent) profile else null),
    )

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long): Long {
            submissions += command to appliesAtTick
            return appliesAtTick
        }
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class StubAgentRegistry(private val agent: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agent
        override fun listForOwner(owner: PlayerId): List<Agent> = listOfNotNull(agent)
    }

    private class StubProfileLookup(private val profile: AgentProfile?) : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile? = profile
    }

    private class StubQuery(
        private val lastLocation: NodeId? = null,
        private val body: BodyView? = null,
        private val starterNode: NodeId? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = body
        override fun starterNodeFor(race: RaceId): NodeId? = starterNode
        override fun randomSpawnableNode(): NodeId? = null
        override fun node(id: NodeId): dev.gvart.genesara.world.Node? = null
        override fun region(id: dev.gvart.genesara.world.RegionId): dev.gvart.genesara.world.Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }
}
