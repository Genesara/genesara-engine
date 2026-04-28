package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookAroundToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val worldId = WorldId(1L)

    private val currentNodeId = NodeId(1L)
    private val northNodeId = NodeId(2L)
    private val farNodeId = NodeId(3L)

    private val region = Region(
        id = regionId,
        worldId = worldId,
        sphereIndex = 0,
        biome = Biome.FOREST,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 0.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private val current = Node(currentNodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(northNodeId))
    private val north = Node(northNodeId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(currentNodeId, farNodeId))
    private val far = Node(farNodeId, regionId, q = 2, r = 0, terrain = Terrain.MOUNTAIN, adjacency = setOf(northNodeId))

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    private val scoutAgent = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "scout",
        apiToken = "token",
        classId = AgentClass.SCOUT,
    )

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agentId)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `returns the current node and adjacent nodes within sight range`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north, farNodeId to far),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertEquals(currentNodeId.value, response.currentNode.id)
        assertEquals(Biome.FOREST.name, response.currentNode.biome)
        assertEquals(Terrain.FOREST.name, response.currentNode.terrain)
        assertEquals(listOf(northNodeId.value), response.adjacent.map { it.id })
        assertTrue(response.adjacent.none { it.id == farNodeId.value })
    }

    @Test
    fun `excludes the agent's own node from the adjacent list`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertTrue(response.adjacent.none { it.id == currentNodeId.value })
    }

    @Test
    fun `returns null biome and climate when the region has not been painted`() {
        val unpainted = region.copy(biome = null, climate = null)
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to unpainted),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertNull(response.currentNode.biome)
        assertNull(response.currentNode.climate)
    }

    @Test
    fun `errors when the agent has not spawned`() {
        val world = StubQuery(
            location = null,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = emptyMap(),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity)

        assertThrows<IllegalStateException> {
            tool.invoke(LookAroundRequest(), toolContext)
        }
    }

    @Test
    fun `errors when the agent is not registered`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
        )
        val tool = LookAroundTool(world, EmptyRegistry, classes(sight = 1), activity)

        assertThrows<IllegalStateException> {
            tool.invoke(LookAroundRequest(), toolContext)
        }
    }

    @Test
    fun `touches the activity registry on every invocation`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity)

        tool.invoke(LookAroundRequest(), toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private fun registryWith(agent: Agent) = object : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun findByToken(token: String): Agent? = if (token == agent.apiToken) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(agent).filter { it.owner == owner }
    }

    private fun classes(sight: Int) = object : ClassPropertiesLookup {
        override fun sightRange(classId: AgentClass?): Int = sight
    }

    private object EmptyRegistry : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
        override fun findByToken(token: String): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubQuery(
        private val location: NodeId?,
        private val nodes: Map<NodeId, Node>,
        private val regions: Map<RegionId, Region>,
        private val within: Map<Pair<NodeId, Int>, Set<NodeId>>,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> =
            within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): dev.gvart.genesara.world.BodyView? = null
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
