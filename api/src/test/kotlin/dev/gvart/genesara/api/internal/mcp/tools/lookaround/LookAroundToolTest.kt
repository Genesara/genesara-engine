package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertEquals(currentNodeId.value, response.currentNode.id)
        assertEquals(Biome.FOREST.name, response.currentNode.biome)
        assertEquals(Terrain.FOREST.name, response.currentNode.terrain)
        assertTrue(response.currentNode.pvpEnabled)
        assertEquals(listOf(northNodeId.value), response.adjacent.map { it.id })
        assertTrue(response.adjacent.none { it.id == farNodeId.value })
    }

    @Test
    fun `surfaces pvpEnabled=false on tiles flagged as green zones`() {
        val safe = current.copy(pvpEnabled = false)
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to safe, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertEquals(false, response.currentNode.pvpEnabled)
        // Non-safe adjacent node still defaults to true.
        assertTrue(response.adjacent.single().pvpEnabled)
    }

    @Test
    fun `records every visible node into agent map memory at the current tick`() {
        // Fog-of-war recall: look_around batches the current tile + every adjacent
        // visible tile into the map-memory gateway so get_map can replay them later.
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val memory = RecordingMapMemory()
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(7L), memory, NoBuildings)

        tool.invoke(LookAroundRequest(), toolContext)

        val recorded = memory.recorded.single()
        assertEquals(agentId, recorded.first)
        assertEquals(7L, recorded.third)
        // Order: current tile first, then adjacent visible tiles. Biome snapshotted
        // alongside terrain so a stale recall reflects what the agent saw.
        assertEquals(
            listOf(
                Triple(currentNodeId, Terrain.FOREST, dev.gvart.genesara.world.Biome.FOREST),
                Triple(northNodeId, Terrain.PLAINS, dev.gvart.genesara.world.Biome.FOREST),
            ),
            recorded.second.map { Triple(it.nodeId, it.terrain, it.biome) },
        )
    }

    @Test
    fun `survives a map-memory write failure — read tool must not be poisoned by a journaling failure`() {
        // Read-tool contract: a DB hiccup in the journaling path can never break
        // observation. The next look_around call re-records everything anyway.
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val flaky = ThrowingMapMemory()
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), flaky, NoBuildings)

        // Should NOT throw — the read still returns successfully.
        val response = tool.invoke(LookAroundRequest(), toolContext)
        assertEquals(currentNodeId.value, response.currentNode.id)
    }

    @Test
    fun `excludes the agent's own node from the adjacent list`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        assertTrue(response.adjacent.none { it.id == currentNodeId.value })
    }

    @Test
    fun `current-node buildings carry full per-instance summaries`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val campfire = activeBuilding(currentNodeId, dev.gvart.genesara.world.BuildingType.CAMPFIRE)
        val buildings = StubBuildingsLookup(byNode = mapOf(currentNodeId to listOf(campfire)))
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), buildings)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        val view = response.currentNode.buildings.single()
        assertEquals("CAMPFIRE", view.type)
        assertEquals("ACTIVE", view.status)
        assertEquals(campfire.instanceId.toString(), view.instanceId)
        assertEquals(5, view.progressSteps)
        assertEquals(5, view.totalSteps)
        assertEquals("high", view.hpBand)
        assertEquals(agentId.id.toString(), view.builderAgentId)
    }

    @Test
    fun `adjacent buildings strip per-instance fields per fog-of-war`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val workbench = activeBuilding(northNodeId, dev.gvart.genesara.world.BuildingType.WORKBENCH)
        val buildings = StubBuildingsLookup(byNode = mapOf(northNodeId to listOf(workbench)))
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), buildings)

        val response = tool.invoke(LookAroundRequest(), toolContext)

        val adjacent = response.adjacent.single { it.id == northNodeId.value }
        val view = adjacent.buildings.single()
        assertEquals("WORKBENCH", view.type)
        assertEquals("ACTIVE", view.status)
        assertEquals(null, view.instanceId)
        assertEquals(null, view.progressSteps)
        assertEquals(null, view.hpBand)
        assertEquals(null, view.builderAgentId)
    }

    @Test
    fun `look_around fetches all visible buildings via a single batched byNodes call`() {
        // The whole point of slice 1's batched method: ONE round-trip per look_around call.
        // The 7-tile fog-of-war hot path turns into 1 query, not 7.
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val recordingBuildings = RecordingBuildingsLookup()
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), recordingBuildings)

        tool.invoke(LookAroundRequest(), toolContext)

        assertEquals(1, recordingBuildings.byNodesCalls.size)
        assertEquals(setOf(currentNodeId, northNodeId), recordingBuildings.byNodesCalls.single())
        assertEquals(0, recordingBuildings.byNodeCalls.size, "must not fall back to per-node lookups")
    }

    private fun activeBuilding(
        node: NodeId,
        type: dev.gvart.genesara.world.BuildingType,
    ): dev.gvart.genesara.world.Building = dev.gvart.genesara.world.Building(
        instanceId = java.util.UUID.randomUUID(),
        nodeId = node,
        type = type,
        status = dev.gvart.genesara.world.BuildingStatus.ACTIVE,
        builtByAgentId = agentId,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = 5,
        totalSteps = 5,
        hpCurrent = 30,
        hpMax = 30,
    )

    private class StubBuildingsLookup(
        private val byNode: Map<NodeId, List<dev.gvart.genesara.world.Building>>,
    ) : dev.gvart.genesara.world.BuildingsLookup {
        override fun byId(id: java.util.UUID): dev.gvart.genesara.world.Building? =
            byNode.values.flatten().firstOrNull { it.instanceId == id }
        override fun byNode(node: NodeId): List<dev.gvart.genesara.world.Building> = byNode[node].orEmpty()
        override fun byNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> =
            nodes.associateWith { byNode[it].orEmpty() }.filterValues { it.isNotEmpty() }
        override fun activeStationsAt(
            node: NodeId,
            hint: dev.gvart.genesara.world.BuildingCategoryHint,
        ): List<dev.gvart.genesara.world.Building> = byNode[node].orEmpty()
    }

    private class RecordingBuildingsLookup : dev.gvart.genesara.world.BuildingsLookup {
        val byNodesCalls = mutableListOf<Set<NodeId>>()
        val byNodeCalls = mutableListOf<NodeId>()
        override fun byId(id: java.util.UUID): dev.gvart.genesara.world.Building? = null
        override fun byNode(node: NodeId): List<dev.gvart.genesara.world.Building> {
            byNodeCalls += node
            return emptyList()
        }
        override fun byNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> {
            byNodesCalls += nodes
            return emptyMap()
        }
        override fun activeStationsAt(
            node: NodeId,
            hint: dev.gvart.genesara.world.BuildingCategoryHint,
        ): List<dev.gvart.genesara.world.Building> = emptyList()
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

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
        val tool = LookAroundTool(world, EmptyRegistry, classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), classes(sight = 1), activity, FixedTickClock(0L), RecordingMapMemory(), NoBuildings)

        tool.invoke(LookAroundRequest(), toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private fun registryWith(agent: Agent) = object : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(agent).filter { it.owner == owner }
    }

    private fun classes(sight: Int) = object : ClassPropertiesLookup {
        override fun sightRange(classId: AgentClass?): Int = sight
    }

    private object EmptyRegistry : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
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
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class FixedTickClock(private val current: Long) : TickClock {
        override fun currentTick(): Long = current
    }

    private class RecordingMapMemory : dev.gvart.genesara.world.AgentMapMemoryGateway {
        val recorded = mutableListOf<Triple<AgentId, List<dev.gvart.genesara.world.NodeMemoryUpdate>, Long>>()
        override fun recordVisible(
            agentId: AgentId,
            updates: Collection<dev.gvart.genesara.world.NodeMemoryUpdate>,
            tick: Long,
        ) {
            recorded += Triple(agentId, updates.toList(), tick)
        }
        override fun recall(agentId: AgentId): List<dev.gvart.genesara.world.RecalledNode> = emptyList()
    }

    private class ThrowingMapMemory : dev.gvart.genesara.world.AgentMapMemoryGateway {
        override fun recordVisible(
            agentId: AgentId,
            updates: Collection<dev.gvart.genesara.world.NodeMemoryUpdate>,
            tick: Long,
        ) {
            throw RuntimeException("simulated DB hiccup during map-memory journaling")
        }
        override fun recall(agentId: AgentId): List<dev.gvart.genesara.world.RecalledNode> = emptyList()
    }

    internal object NoBuildings : dev.gvart.genesara.world.BuildingsLookup {
        override fun byId(id: java.util.UUID): dev.gvart.genesara.world.Building? = null
        override fun byNode(node: NodeId): List<dev.gvart.genesara.world.Building> = emptyList()
        override fun byNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> = emptyMap()
        override fun activeStationsAt(
            node: NodeId,
            hint: dev.gvart.genesara.world.BuildingCategoryHint,
        ): List<dev.gvart.genesara.world.Building> = emptyList()
    }
}
