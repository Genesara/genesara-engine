package dev.gvart.genesara.api.internal.mcp.tools.harvest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Crop
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.PlantedCrop
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceItemId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarvestToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    private fun newTool(
        plots: AgentPlotsStore = NoOpPlotsStore,
        crops: CropLookup = NoOpCropLookup,
        query: WorldQueryGateway = NullWorldQuery,
    ) = HarvestTool(gateway, tickClock, activity, query, plots, crops)

    @Test
    fun `queues a Harvest command at the next tick and returns the ack`() {
        val tool = newTool()

        val response = tool.invoke(ResourceItemId.WOOD, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals("WOOD", response.itemId)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val harvest = assertNotNull(cmd as? WorldCommand.Harvest)
        assertEquals(agent, harvest.agent)
        assertEquals(ItemId("WOOD"), harvest.item)
        assertEquals(51L, appliesAt)
        assertEquals(harvest.commandId, response.commandId)
    }

    @Test
    fun `accepts a previously-mining-only item — single verb covers every harvest`() {
        val tool = newTool()

        val response = tool.invoke(ResourceItemId.STONE, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals("STONE", response.itemId)
        val cmd = gateway.submissions.single().first as WorldCommand.Harvest
        assertEquals(ItemId("STONE"), cmd.item)
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = newTool()

        assertTrue(agent !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(ResourceItemId.WOOD, toolContext)

        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    @Test
    fun `dispatches to HarvestCrop when a ripe FARM_PLOT on this node matches the itemId`() {
        val nodeId = NodeId(1L)
        val plotId = UUID.randomUUID()
        val plot = AgentPlot(
            plotId = plotId,
            buildingInstanceId = UUID.randomUUID(),
            agentId = agent,
            nodeId = nodeId,
            plant = PlantedCrop(CropId("WHEAT"), plantedAtTick = 0L, lastTendedAtTick = 0L),
        )
        val plots = StubPlotsStore(mapOf(nodeId to listOf(plot)))
        val crops = StubCropLookup(wheatCrop)
        // currentTickFor returns 60, plantedAt 0 + ticksToRipe 60 = 60 → ripe.
        val query = StubWorldQuery(location = nodeId, currentTick = 60L)
        val tool = newTool(plots, crops, query)

        val response = tool.invoke(ResourceItemId.WHEAT, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = gateway.submissions.single().first
        val harvest = assertNotNull(cmd as? WorldCommand.HarvestCrop)
        assertEquals(plotId, harvest.plotId)
    }

    @Test
    fun `falls through to resource-cell Harvest when the plot is not yet ripe`() {
        val nodeId = NodeId(1L)
        val plot = AgentPlot(
            plotId = UUID.randomUUID(),
            buildingInstanceId = UUID.randomUUID(),
            agentId = agent,
            nodeId = nodeId,
            plant = PlantedCrop(CropId("WHEAT"), plantedAtTick = 0L, lastTendedAtTick = 0L),
        )
        val plots = StubPlotsStore(mapOf(nodeId to listOf(plot)))
        val crops = StubCropLookup(wheatCrop)
        // tick 30 < plantedAt 0 + ticksToRipe 60 → not ripe.
        val query = StubWorldQuery(location = nodeId, currentTick = 30L)
        val tool = newTool(plots, crops, query)

        val response = tool.invoke(ResourceItemId.WHEAT, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = gateway.submissions.single().first
        assertNotNull(cmd as? WorldCommand.Harvest)
    }

    @Test
    fun `falls through to resource-cell Harvest when no FARM_PLOT on this node matches the itemId`() {
        val nodeId = NodeId(1L)
        // Plot with WHEAT planted but caller asks for WOOD.
        val plot = AgentPlot(
            plotId = UUID.randomUUID(),
            buildingInstanceId = UUID.randomUUID(),
            agentId = agent,
            nodeId = nodeId,
            plant = PlantedCrop(CropId("WHEAT"), plantedAtTick = 0L, lastTendedAtTick = 0L),
        )
        val plots = StubPlotsStore(mapOf(nodeId to listOf(plot)))
        val crops = StubCropLookup(wheatCrop)
        val query = StubWorldQuery(location = nodeId, currentTick = 100L)
        val tool = newTool(plots, crops, query)

        val response = tool.invoke(ResourceItemId.WOOD, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = gateway.submissions.single().first
        val harvest = assertNotNull(cmd as? WorldCommand.Harvest)
        assertEquals(ItemId("WOOD"), harvest.item)
    }

    private val wheatCrop = Crop(
        id = CropId("WHEAT"),
        seedItem = ItemId("WHEAT_SEED"),
        ticksToRipe = 60,
        outputItem = ItemId("WHEAT"),
        baseYield = 4,
        neglectWindowTicks = 30,
        requiredTerrain = setOf(Terrain.PLAINS),
        requiredFarmingLevel = 0,
        gainPerLevel = 0.0,
        maxLuckBonus = 0,
        staminaCostPlant = 6,
        staminaCostTend = 4,
        staminaCostHarvest = 8,
        farmingSkill = SkillId("FARMING"),
    )

    private object NoOpPlotsStore : AgentPlotsStore {
        override fun insertEmpty(plot: AgentPlot) = error("not used")
        override fun findById(plotId: UUID): AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: UUID): AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>> = emptyMap()
        override fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot? = null
        override fun tend(plotId: UUID, tick: Long): AgentPlot? = null
        override fun clearPlanting(plotId: UUID): AgentPlot? = null
        override fun listPlantedSnapshot(): List<AgentPlot> = emptyList()
    }

    private object NoOpCropLookup : CropLookup {
        override fun byId(id: CropId): Crop? = null
        override fun all(): List<Crop> = emptyList()
    }

    private object NullWorldQuery : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class StubPlotsStore(private val byNode: Map<NodeId, List<AgentPlot>>) : AgentPlotsStore {
        override fun insertEmpty(plot: AgentPlot) = error("not used")
        override fun findById(plotId: UUID): AgentPlot? =
            byNode.values.flatten().firstOrNull { it.plotId == plotId }
        override fun findByBuilding(buildingInstanceId: UUID): AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>> =
            byNode.filterKeys { it in nodes }
        override fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot? = null
        override fun tend(plotId: UUID, tick: Long): AgentPlot? = null
        override fun clearPlanting(plotId: UUID): AgentPlot? = null
        override fun listPlantedSnapshot(): List<AgentPlot> = byNode.values.flatten().filter { it.plant != null }
    }

    private class StubCropLookup(vararg crops: Crop) : CropLookup {
        private val byId = crops.associateBy { it.id }
        override fun byId(id: CropId): Crop? = byId[id]
        override fun all(): List<Crop> = byId.values.toList()
    }

    private class StubWorldQuery(
        private val location: NodeId?,
        private val currentTick: Long,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = currentTick
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

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
}
