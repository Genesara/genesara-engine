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
import dev.gvart.genesara.world.VisibleNodes
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        val response = tool.invoke(toolContext)

        assertEquals(currentNodeId.value, response.currentNode.id)
        assertEquals(Biome.FOREST, response.currentNode.biome)
        assertEquals(Terrain.FOREST, response.currentNode.terrain)
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 2, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, memory, NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, flaky, NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        val response = tool.invoke(toolContext)

        assertTrue(response.visible.none { it.id == currentNodeId.value })
    }

    @Test
    fun `current-node FARM_PLOT carries plotId plus crop fields when planted`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
            currentTick = 30L,
        )
        val plot = activeBuilding(currentNodeId, dev.gvart.genesara.world.BuildingType.FARM_PLOT)
        val buildings = StubBuildingsLookup(byNode = mapOf(currentNodeId to listOf(plot)))
        val plotId = java.util.UUID.randomUUID()
        val plotsByNode = StubPlotsByNode(
            mapOf(
                currentNodeId to listOf(
                    dev.gvart.genesara.world.AgentPlot(
                        plotId = plotId,
                        buildingInstanceId = plot.instanceId,
                        nodeId = currentNodeId,
                        plant = dev.gvart.genesara.world.PlantedCrop(
                            cropId = dev.gvart.genesara.world.CropId("WHEAT"),
                            plantedAtTick = 0L,
                            lastTendedAtTick = 10L,
                            plantedByAgentId = agentId,
                        ),
                    ),
                ),
            ),
        )
        val crops = StubCrops(wheatCrop)
        val tool = LookAroundTool(
            world,
            registryWith(scoutAgent),
            vision(sight = 1, world),
            activity,
            RecordingMapMemory(),
            buildings,
            plotsByNode,
            crops,
            NoGates,
            NoMounts,
        )

        val view = tool.invoke(toolContext).currentNode.buildings.single()

        assertEquals("FARM_PLOT", view.type)
        assertEquals(plotId.toString(), view.plotId)
        assertEquals("WHEAT", view.plantedCrop)
        // Planted at 0, ticksToRipe 60, currentTick 30 → 30 ticks to ripe.
        assertEquals(30L, view.ticksToRipe)
        // Tended at 10, neglect window 30 → 30 ticks until neglect at currentTick 30.
        assertEquals(10L, view.ticksUntilNeglect)
    }

    @Test
    fun `current-node FARM_PLOT carries plotId but null crop fields when empty`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
            currentTick = 30L,
        )
        val plot = activeBuilding(currentNodeId, dev.gvart.genesara.world.BuildingType.FARM_PLOT)
        val buildings = StubBuildingsLookup(byNode = mapOf(currentNodeId to listOf(plot)))
        val plotId = java.util.UUID.randomUUID()
        val plotsByNode = StubPlotsByNode(
            mapOf(
                currentNodeId to listOf(
                    dev.gvart.genesara.world.AgentPlot(
                        plotId = plotId,
                        buildingInstanceId = plot.instanceId,
                        nodeId = currentNodeId,
                        plant = null,
                    ),
                ),
            ),
        )
        val tool = LookAroundTool(
            world, registryWith(scoutAgent), vision(sight = 1, world), activity,
            RecordingMapMemory(), buildings, plotsByNode, NoOpCrops, NoGates, NoMounts,
        )

        val view = tool.invoke(toolContext).currentNode.buildings.single()

        assertEquals(plotId.toString(), view.plotId)
        assertEquals(null, view.plantedCrop)
        assertEquals(null, view.ticksToRipe)
        assertEquals(null, view.ticksUntilNeglect)
    }

    @Test
    fun `adjacent FARM_PLOT shows the planted crop name only — timing details suppressed`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
            currentTick = 30L,
        )
        val plot = activeBuilding(northNodeId, dev.gvart.genesara.world.BuildingType.FARM_PLOT)
        val buildings = StubBuildingsLookup(byNode = mapOf(northNodeId to listOf(plot)))
        val plotsByNode = StubPlotsByNode(
            mapOf(
                northNodeId to listOf(
                    dev.gvart.genesara.world.AgentPlot(
                        plotId = java.util.UUID.randomUUID(),
                        buildingInstanceId = plot.instanceId,
                        nodeId = northNodeId,
                        plant = dev.gvart.genesara.world.PlantedCrop(
                            cropId = dev.gvart.genesara.world.CropId("WHEAT"),
                            plantedAtTick = 0L,
                            lastTendedAtTick = 10L,
                            plantedByAgentId = agentId,
                        ),
                    ),
                ),
            ),
        )
        val tool = LookAroundTool(
            world, registryWith(scoutAgent), vision(sight = 1, world), activity,
            RecordingMapMemory(), buildings, plotsByNode, StubCrops(wheatCrop), NoGates, NoMounts,
        )

        val view = tool.invoke(toolContext).visible.single { it.id == northNodeId.value }.buildings.single()

        assertEquals("FARM_PLOT", view.type)
        assertEquals("WHEAT", view.plantedCrop)
        assertEquals(null, view.plotId)
        assertEquals(null, view.ticksToRipe)
        assertEquals(null, view.ticksUntilNeglect)
        // Standard fog-of-war: instance details still suppressed.
        assertEquals(null, view.instanceId)
    }

    private val wheatCrop = dev.gvart.genesara.world.Crop(
        id = dev.gvart.genesara.world.CropId("WHEAT"),
        seedItem = dev.gvart.genesara.world.ItemId("WHEAT_SEED"),
        ticksToRipe = 60,
        outputItem = dev.gvart.genesara.world.ItemId("WHEAT"),
        baseYield = 4,
        neglectWindowTicks = 30,
        requiredTerrain = setOf(dev.gvart.genesara.world.Terrain.PLAINS),
        requiredFarmingLevel = 0,
        gainPerLevel = 0.0,
        maxLuckBonus = 0,
        staminaCostPlant = 6,
        staminaCostTend = 4,
        staminaCostHarvest = 8,
        farmingSkill = dev.gvart.genesara.player.SkillId("FARMING"),
    )

    private class StubPlotsByNode(
        private val byNode: Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>>,
    ) : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = error("not used")
        override fun findById(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? =
            byNode.values.flatten().firstOrNull { it.plotId == plotId }
        override fun findByBuilding(buildingInstanceId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? =
            byNode.values.flatten().firstOrNull { it.buildingInstanceId == buildingInstanceId }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> =
            byNode.filterKeys { it in nodes }
        override fun plant(
            plotId: java.util.UUID,
            crop: dev.gvart.genesara.world.PlantedCrop,
        ): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: java.util.UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> =
            byNode.values.flatten().filter { it.plant != null }
    }

    private class StubCrops(vararg crops: dev.gvart.genesara.world.Crop) : dev.gvart.genesara.world.CropLookup {
        private val byId = crops.associateBy { it.id }
        override fun byId(id: dev.gvart.genesara.world.CropId): dev.gvart.genesara.world.Crop? = byId[id]
        override fun all(): List<dev.gvart.genesara.world.Crop> = byId.values.toList()
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), buildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        val response = tool.invoke(toolContext)

        val view = response.currentNode.buildings.single()
        assertEquals("CAMPFIRE", view.type)
        assertEquals("ACTIVE", view.status)
        assertEquals(campfire.instanceId.toString(), view.instanceId)
        assertEquals(5, view.progressSteps)
        assertEquals(5, view.totalSteps)
        assertEquals("high", view.hpBand)
        assertEquals("agent:${agentId.id}", view.builderAgentId)
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), buildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
    fun `current-tile GATE surfaces isOpen=true when the gate state store reports OPEN`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val gate = activeBuilding(currentNodeId, dev.gvart.genesara.world.BuildingType.GATE)
        val buildings = StubBuildingsLookup(byNode = mapOf(currentNodeId to listOf(gate)))
        val gates = StubGates(open = setOf(gate.instanceId))
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), buildings, NoOpPlots, NoOpCrops, gates, NoMounts)

        val view = tool.invoke(toolContext).currentNode.buildings.single()

        assertEquals("GATE", view.type)
        assertEquals(true, view.isOpen)
    }

    @Test
    fun `adjacent GATE surfaces isOpen too — gate visibility carries across sight range`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val gate = activeBuilding(northNodeId, dev.gvart.genesara.world.BuildingType.GATE)
        val buildings = StubBuildingsLookup(byNode = mapOf(northNodeId to listOf(gate)))
        val gates = StubGates(closed = setOf(gate.instanceId))
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), buildings, NoOpPlots, NoOpCrops, gates, NoMounts)

        val visible = tool.invoke(toolContext).visible.single { it.id == northNodeId.value }
        val view = visible.buildings.single()

        assertEquals("GATE", view.type)
        assertEquals(false, view.isOpen)
        // Fog-of-war contract still holds for everything else.
        assertEquals(null, view.instanceId)
        assertEquals(null, view.builderAgentId)
    }

    @Test
    fun `non-GATE buildings never carry isOpen, even on the current tile`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val workbench = activeBuilding(currentNodeId, dev.gvart.genesara.world.BuildingType.WORKBENCH)
        val buildings = StubBuildingsLookup(byNode = mapOf(currentNodeId to listOf(workbench)))
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), buildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        val view = tool.invoke(toolContext).currentNode.buildings.single()

        assertEquals(null, view.isOpen)
    }

    @Test
    fun `look_around fetches all visible buildings via a single batched byNodes call plus one byNode for vision`() {
        // The visible-node fog-of-war hot path remains a single batched byNodes call. A
        // single byNode(currentNodeId) precomputes whether a WATCHTOWER at the current node
        // contributes the +2 sight bonus before the visible set is even known — this is
        // not a per-node-loop fallback.
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val recordingBuildings = RecordingBuildingsLookup()
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), recordingBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        tool.invoke(toolContext)

        assertEquals(1, recordingBuildings.byNodesCalls.size)
        assertEquals(setOf(currentNodeId, northNodeId), recordingBuildings.byNodesCalls.single())
        assertEquals(listOf(currentNodeId), recordingBuildings.byNodeCalls, "exactly one byNode for vision; visible nodes go through the batched call")
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
            vision(sight = 1, world),
            activity,
            RecordingMapMemory(),
            NoBuildings,
            NoOpPlots, NoOpCrops, NoGates, NoMounts,
        )

        val response = tool.invoke(toolContext)

        val agents = response.currentNode.agents
        assertEquals(2, agents.size)
        assertEquals("agent:${firstOtherId.id}", agents[0].id)
        assertEquals("alice", agents[0].name)
        assertEquals("human_warden", agents[0].race)
        assertEquals(4, agents[0].level)
        assertEquals("high", agents[0].hpBand)
        assertEquals("agent:${secondOtherId.id}", agents[1].id)
        assertEquals("low", agents[1].hpBand)
        assertTrue(agents.none { it.id == "agent:${agentId.id}" })
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
            vision(sight = 1, world),
            activity,
            RecordingMapMemory(),
            NoBuildings,
            NoOpPlots, NoOpCrops, NoGates, NoMounts,
        )

        val response = tool.invoke(toolContext)

        val agents = response.currentNode.agents
        assertEquals(1, agents.size)
        assertEquals("agent:${knownOtherId.id}", agents.single().id)
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
            vision(sight = 1, world),
            activity,
            RecordingMapMemory(),
            NoBuildings,
            NoOpPlots, NoOpCrops, NoGates, NoMounts,
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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

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
        val tool = LookAroundTool(world, EmptyRegistry, vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        assertThrows<IllegalStateException> {
            tool.invoke(toolContext)
        }
    }

    @Test
    fun `current node npcs surface with npc-prefixed ids`() {
        val npcId = dev.gvart.genesara.world.NpcId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val deer = dev.gvart.genesara.world.Npc(
            id = npcId,
            type = dev.gvart.genesara.world.NpcType("DEER"),
            nodeId = currentNodeId, spawnNodeId = currentNodeId,
            hpCurrent = 20, hpMax = 20, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        val deerDef = dev.gvart.genesara.world.NpcDef(
            type = dev.gvart.genesara.world.NpcType("DEER"), displayName = "Deer",
            hpMax = 20, damage = 0, damageType = dev.gvart.genesara.world.DamageType.BLUNT,
            range = 1, attackIntervalTicks = 99, defense = 0, dodgeChancePercent = 25,
            aggressionProfile = dev.gvart.genesara.world.AggressionProfile.PASSIVE,
        )
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
            npcs = mapOf(currentNodeId to listOf(deer)),
            npcDef = deerDef,
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        val response = tool.invoke(toolContext)

        val view = response.currentNode.npcs.single()
        assertEquals("npc:${npcId.value}", view.id)
        assertEquals("DEER", view.type)
        assertEquals("Deer", view.displayName)
        assertEquals("PASSIVE", view.aggression)
    }

    @Test
    fun `mounts section surfaces every visible mount with prefixed id, ridden flag and at-node`() {
        val mountIdA = dev.gvart.genesara.world.MountId(java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        val mountIdB = dev.gvart.genesara.world.MountId(java.util.UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
        val ownerId = AgentId(java.util.UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"))
        val riderId = AgentId(java.util.UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"))
        val ridden = mount(mountIdA, currentNodeId, rider = riderId, type = "RIDING_HORSE")
        val idle = mount(mountIdB, northNodeId, rider = null, type = "ELK_MOUNT")
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val mounts = StubMounts(mapOf(currentNodeId to listOf(ridden), northNodeId to listOf(idle)))
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, mounts)

        val response = tool.invoke(toolContext)

        val views = response.mounts.associateBy { it.id }
        assertEquals(2, views.size)
        val a = views.getValue("mount:${mountIdA.value}")
        assertEquals("RIDING_HORSE", a.type)
        assertTrue(a.ridden)
        assertEquals(currentNodeId.value, a.at)
        val b = views.getValue("mount:${mountIdB.value}")
        assertEquals(false, b.ridden)
        assertEquals(northNodeId.value, b.at)
    }

    @Test
    fun `mounts section is empty when no mounts live in any visible node`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current, northNodeId to north),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, northNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, StubMounts(emptyMap()))

        val response = tool.invoke(toolContext)

        assertEquals(emptyList(), response.mounts)
    }

    private fun mount(
        id: dev.gvart.genesara.world.MountId,
        at: NodeId,
        rider: AgentId?,
        type: String,
    ) = dev.gvart.genesara.world.Mount(
        id = id,
        type = dev.gvart.genesara.world.MountType(type),
        nodeId = at,
        hpCurrent = 30, hpMax = 30,
        hunger = 50, hungerMax = 100,
        fatigue = 0, fatigueMax = 80,
        mountedByAgentId = rider,
        tamedAtTick = 0L,
    )

    @Test
    fun `touches the activity registry on every invocation`() {
        val world = StubQuery(
            location = currentNodeId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
        )
        val tool = LookAroundTool(world, registryWith(scoutAgent), vision(sight = 1, world), activity, RecordingMapMemory(), NoBuildings, NoOpPlots, NoOpCrops, NoGates, NoMounts)

        tool.invoke(toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private fun registryWith(vararg entries: Agent) = object : AgentRegistry {
        private val byId = entries.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = entries.filter { it.owner == owner }
    }

    /**
     * Test stub for [VisibleNodes]. Reads the visible set from the StubQuery's
     * `within` map at the given [sight] — same data shape the radius-era tests
     * already wire up, so this stays minimally invasive.
     */
    private fun vision(sight: Int, query: dev.gvart.genesara.world.WorldQueryGateway? = null) = object : VisibleNodes {
        override fun visibleNodesFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<dev.gvart.genesara.world.Building>,
        ): Set<NodeId> = query?.nodesWithin(currentNode, sight) ?: setOf(currentNode)
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
        private val npcs: Map<NodeId, List<dev.gvart.genesara.world.Npc>> = emptyMap(),
        private val npcDef: dev.gvart.genesara.world.NpcDef? = null,
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
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.Npc>> =
            nodeIds.associateWith { npcs[it].orEmpty() }.filterValues { it.isNotEmpty() }
        override fun npcDef(type: dev.gvart.genesara.world.NpcType): dev.gvart.genesara.world.NpcDef? = npcDef
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

    internal object NoOpPlots : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = error("not used")
        override fun findById(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(
            plotId: java.util.UUID,
            crop: dev.gvart.genesara.world.PlantedCrop,
        ): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: java.util.UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    internal object NoOpCrops : dev.gvart.genesara.world.CropLookup {
        override fun byId(id: dev.gvart.genesara.world.CropId): dev.gvart.genesara.world.Crop? = null
        override fun all(): List<dev.gvart.genesara.world.Crop> = emptyList()
    }

    internal object NoGates : dev.gvart.genesara.world.BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: java.util.UUID) = error("not used")
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = null
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = null
    }

    internal class StubGates(
        open: Set<java.util.UUID> = emptySet(),
        closed: Set<java.util.UUID> = emptySet(),
    ) : dev.gvart.genesara.world.BuildingGateStateStore {
        private val state: Map<java.util.UUID, Boolean> =
            open.associateWith { true } + closed.associateWith { false }
        override fun insertClosed(gateInstanceId: java.util.UUID) = error("not used")
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = state[gateInstanceId]
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = error("not used")
    }

    internal val NoMounts: dev.gvart.genesara.world.MountInstanceStore =
        dev.gvart.genesara.world.MountInstanceStore.NoOp

    internal class StubMounts(
        private val byNode: Map<NodeId, List<dev.gvart.genesara.world.Mount>>,
    ) : dev.gvart.genesara.world.MountInstanceStore {
        override fun insert(mount: dev.gvart.genesara.world.Mount) = error("not used")
        override fun findById(mountId: dev.gvart.genesara.world.MountId): dev.gvart.genesara.world.Mount? =
            byNode.values.flatten().firstOrNull { it.id == mountId }
        override fun byNodes(nodeIds: Collection<NodeId>): List<dev.gvart.genesara.world.Mount> =
            nodeIds.flatMap { byNode[it].orEmpty() }
        override fun findByRider(agentId: AgentId): dev.gvart.genesara.world.Mount? = null
        override fun all(): List<dev.gvart.genesara.world.Mount> = byNode.values.flatten()
        override fun delete(mountId: dev.gvart.genesara.world.MountId): Boolean = false
        override fun update(mount: dev.gvart.genesara.world.Mount): Boolean = false
    }
}
