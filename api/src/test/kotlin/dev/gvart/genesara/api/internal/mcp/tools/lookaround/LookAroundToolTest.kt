package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.VisionRadius
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(toolContext)

        assertEquals(currentNodeId.value, response.currentNode.id)
        assertEquals(Biome.FOREST.name, response.currentNode.biome)
        assertEquals(Terrain.FOREST.name, response.currentNode.terrain)
        assertTrue(response.currentNode.pvpEnabled)
        assertEquals(listOf(northNodeId.value), response.visible.map { it.id })
        assertTrue(response.visible.none { it.id == farNodeId.value })
    }

    @Test
    fun `neighbours lists only the hex-adjacent move-legal targets, not the wider vision radius`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north, farNodeId to far),
            regions = mapOf(regionId to region),
            // Sight 2 surfaces `far` in `visible` but it is not move-adjacent to the current node.
            within = mapOf((currentNodeId to 2) to setOf(currentNodeId, northNodeId, farNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 2), activity, RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(toolContext)

        assertEquals(listOf(northNodeId.value, farNodeId.value).sorted(), response.visible.map { it.id }.sorted())
        assertEquals(listOf(northNodeId.value), response.neighbours)
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(toolContext)

        assertEquals(false, response.currentNode.pvpEnabled)
        // Non-safe adjacent node still defaults to true.
        assertTrue(response.visible.single().pvpEnabled)
    }

    @Test
    fun `records every visible node into agent map memory at the per-world tick`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            currentTick = 7L,
        )
        val memory = RecordingMapMemory()
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, memory, NoBuildings)

        tool.invoke(toolContext)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, flaky, NoBuildings)

        // Should NOT throw — the read still returns successfully.
        val response = tool.invoke(toolContext)
        assertEquals(currentNodeId.value, response.currentNode.id)
    }

    @Test
    fun `resourcesAt is queried at the per-world tick so lazy-regen aligns with the event clock`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            currentTick = 9_999L,
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        tool.invoke(toolContext)

        assertTrue(world.resourcesAtCalls.isNotEmpty())
        assertTrue(world.resourcesAtCalls.all { it.second == 9_999L })
    }

    @Test
    fun `excludes the agent's own node from the adjacent list`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(toolContext)

        assertTrue(response.visible.none { it.id == currentNodeId.value })
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), buildings)

        val response = tool.invoke(toolContext)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), buildings)

        val response = tool.invoke(toolContext)

        val visible = response.visible.single { it.id == northNodeId.value }
        val view = visible.buildings.single()
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), recordingBuildings)

        tool.invoke(toolContext)

        assertEquals(1, recordingBuildings.byNodesCalls.size)
        assertEquals(setOf(currentNodeId, northNodeId), recordingBuildings.byNodesCalls.single())
        assertEquals(0, recordingBuildings.byNodeCalls.size, "must not fall back to per-node lookups")
    }

    @Test
    fun `currentNode agents lists co-located agents excluding self, sorted by id, with hpBand only`() {
        val firstOtherId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val secondOtherId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val firstOther = scoutAgent.copy(
            id = firstOtherId,
            name = "alice",
            race = dev.gvart.genesara.player.RaceId("human_warden"),
            level = 4,
        )
        val secondOther = scoutAgent.copy(
            id = secondOtherId,
            name = "bob",
            race = dev.gvart.genesara.player.RaceId("human_commoner"),
            level = 7,
        )
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            occupants = mapOf(currentNodeId to listOf(secondOtherId, firstOtherId, agentId)),
            bodies = mapOf(
                firstOtherId to bodyAt(hp = 95, maxHp = 100),
                secondOtherId to bodyAt(hp = 15, maxHp = 100),
            ),
        )
        val tool = LookAroundTool(
            world,
            registryWith(scoutAgent, firstOther, secondOther),
            vision(sight = 1),
            activity,
            FixedTickClock(0L),
            RecordingMapMemory(),
            NoBuildings,
        )

        val response = tool.invoke(toolContext)

        val agents = response.currentNode.agents
        assertEquals(2, agents.size)
        assertEquals(firstOtherId.id.toString(), agents[0].id)
        assertEquals("alice", agents[0].name)
        assertEquals("human_warden", agents[0].race)
        assertEquals(4, agents[0].level)
        assertEquals("high", agents[0].hpBand)
        assertEquals(secondOtherId.id.toString(), agents[1].id)
        assertEquals("low", agents[1].hpBand)
        assertTrue(agents.none { it.id == agentId.id.toString() })
    }

    @Test
    fun `currentNode agents skips entries whose agent row or body is missing`() {
        val knownOtherId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"))
        val ghostId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000020"))
        val bodylessId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000030"))
        val knownOther = scoutAgent.copy(id = knownOtherId, name = "known", level = 2)
        val bodyless = scoutAgent.copy(id = bodylessId, name = "bodyless", level = 5)
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
            occupants = mapOf(currentNodeId to listOf(knownOtherId, ghostId, bodylessId)),
            bodies = mapOf(knownOtherId to bodyAt(hp = 50, maxHp = 100)),
        )
        val tool = LookAroundTool(
            world,
            registryWith(scoutAgent, knownOther, bodyless),
            vision(sight = 1),
            activity,
            FixedTickClock(0L),
            RecordingMapMemory(),
            NoBuildings,
        )

        val response = tool.invoke(toolContext)

        val agents = response.currentNode.agents
        assertEquals(1, agents.size)
        assertEquals(knownOtherId.id.toString(), agents.single().id)
    }

    @Test
    fun `visible adjacent nodes never carry agent presence`() {
        val otherId = AgentId(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
        val other = scoutAgent.copy(id = otherId, name = "scout-north")
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            occupants = mapOf(northNodeId to listOf(otherId)),
            bodies = mapOf(otherId to bodyAt(hp = 100, maxHp = 100)),
        )
        val tool = LookAroundTool(
            world,
            registryWith(scoutAgent, other),
            vision(sight = 1),
            activity,
            FixedTickClock(0L),
            RecordingMapMemory(),
            NoBuildings,
        )

        val response = tool.invoke(toolContext)

        assertEquals(emptyList(), response.currentNode.agents)
        assertTrue(response.visible.single().agents.isEmpty())
    }

    private fun bodyAt(hp: Int, maxHp: Int) = dev.gvart.genesara.world.BodyView(
        hp = hp, maxHp = maxHp,
        stamina = 0, maxStamina = 1,
        mana = 0, maxMana = 1,
        hunger = 0, maxHunger = 1,
        thirst = 0, maxThirst = 1,
        sleep = 0, maxSleep = 1,
    )

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        val response = tool.invoke(toolContext)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        assertThrows<IllegalStateException> {
            tool.invoke(toolContext)
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
        val tool = LookAroundTool(world, EmptyRegistry, vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        assertThrows<IllegalStateException> {
            tool.invoke(toolContext)
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1), activity, RecordingMapMemory(), NoBuildings)

        tool.invoke(toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private fun registryWith(vararg entries: Agent) = object : AgentRegistry {
        private val byId = entries.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = entries.filter { it.owner == owner }
    }

    private fun vision(sight: Int) = object : VisionRadius {
        override fun radiusFor(agent: Agent, currentNode: NodeId): Int = sight
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
        private val currentTick: Long = 0L,
        val resourcesAtCalls: MutableList<Pair<NodeId, Long>> = mutableListOf(),
        private val occupants: Map<NodeId, List<AgentId>> = emptyMap(),
        private val bodies: Map<AgentId, dev.gvart.genesara.world.BodyView> = emptyMap(),
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> =
            within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): dev.gvart.genesara.world.BodyView? = bodies[agent]
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources {
            resourcesAtCalls += nodeId to tick
            return dev.gvart.genesara.world.NodeResources.EMPTY
        }
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = currentTick
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> =
            nodeIds.associateWith { occupants[it].orEmpty() }.filterValues { it.isNotEmpty() }
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
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
