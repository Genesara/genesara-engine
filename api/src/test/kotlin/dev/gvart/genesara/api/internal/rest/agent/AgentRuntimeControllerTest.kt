package dev.gvart.genesara.api.internal.rest.agent

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.engine.TickClock
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
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentRuntimeControllerTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val agent = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "alpha",
        apiToken = "token",
        classId = AgentClass.SCOUT,
    )

    private val regionId = RegionId(1L)
    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 0.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val currentNodeId = NodeId(1L)
    private val northNodeId = NodeId(2L)
    private val current = Node(currentNodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(northNodeId))
    private val north = Node(northNodeId, regionId, q = 1, r = 0, terrain = Terrain.HILLS, adjacency = setOf(currentNodeId))

    private val recordingGateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 100L)

    @Test
    fun `spawn returns 202 ACCEPTED with command id and applies-at tick`() {
        val controller = controller(query = StubQuery())

        val response = controller.spawn(agent, AgentRuntimeController.CommandRequest(currentNodeId.value))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val (cmd, appliesAt) = recordingGateway.submissions.single()
        val spawn = cmd as WorldCommand.SpawnAgent
        assertEquals(agentId, spawn.agent)
        assertEquals(currentNodeId, spawn.at)
        assertEquals(101L, appliesAt)
        assertEquals(spawn.commandId, response.body!!.commandId)
        assertEquals(101L, response.body!!.appliesAtTick)
    }

    @Test
    fun `move returns 202 ACCEPTED with command id and applies-at tick`() {
        val controller = controller(query = StubQuery())

        val response = controller.move(agent, AgentRuntimeController.CommandRequest(northNodeId.value))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val move = recordingGateway.submissions.single().first as WorldCommand.MoveAgent
        assertEquals(northNodeId, move.to)
        assertEquals(101L, response.body!!.appliesAtTick)
    }

    @Test
    fun `look-around returns 200 with current node and visible adjacent nodes`() {
        val controller = controller(
            query = StubQuery(
                location = currentNodeId,
                nodes = mapOf(currentNodeId to current, northNodeId to north),
                regions = mapOf(regionId to region),
                within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            ),
        )

        val response = controller.lookAround(agent)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = assertNotNull(response.body)
        assertEquals(currentNodeId.value, body.currentNode.id)
        assertEquals(listOf(northNodeId.value), body.adjacent.map { it.id })
    }

    @Test
    fun `look-around returns 404 when the agent is not registered`() {
        val controller = AgentRuntimeController(
            command = recordingGateway,
            query = StubQuery(location = currentNodeId, nodes = mapOf(currentNodeId to current), regions = mapOf(regionId to region)),
            tick = tickClock,
            agents = EmptyRegistry,
            classes = constantSight(1),
        )

        val response = controller.lookAround(agent)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `look-around returns 409 CONFLICT when the agent has not spawned`() {
        val controller = controller(query = StubQuery(location = null))

        val response = controller.lookAround(agent)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `look-around returns 404 when the agent's current node is missing from the read model`() {
        val controller = controller(
            query = StubQuery(location = currentNodeId, nodes = emptyMap(), regions = mapOf(regionId to region)),
        )

        val response = controller.lookAround(agent)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    private fun controller(query: StubQuery) = AgentRuntimeController(
        command = recordingGateway,
        query = query,
        tick = tickClock,
        agents = SingleAgentRegistry(agent),
        classes = constantSight(1),
    )

    private fun constantSight(sight: Int) = object : ClassPropertiesLookup {
        override fun sightRange(classId: AgentClass?): Int = sight
    }

    private class SingleAgentRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun findByToken(token: String): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private object EmptyRegistry : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
        override fun findByToken(token: String): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long) {
            submissions += command to appliesAtTick
        }
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class StubQuery(
        private val location: NodeId? = null,
        private val nodes: Map<NodeId, Node> = emptyMap(),
        private val regions: Map<RegionId, Region> = emptyMap(),
        private val within: Map<Pair<NodeId, Int>, Set<NodeId>> = emptyMap(),
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): dev.gvart.genesara.world.BodyView? = null
    }
}
