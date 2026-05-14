package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.VisionRadius
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Terrain
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
import kotlin.test.assertNull

class InspectBuildingTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val builderId = AgentId(UUID.randomUUID())
    private val nodeId = NodeId(1L)
    private val outOfSightNodeId = NodeId(99L)
    private val regionId = RegionId(1L)

    private val toolContext = ToolContext(emptyMap())
    private val activity = AgentActivityRegistry(MutableTestClock(Instant.parse("2026-01-01T00:00:00Z")))

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `SHALLOW perception still surfaces per-instance fields — parity with look_around`() {
        val chest = building(builder = builderId, type = BuildingType.STORAGE_CHEST)
        val tool = tool(perception = 0, buildings = listOf(chest))

        val resp = tool.dispatch("building", chest.instanceId.toString(), toolContext)

        val view = assertNotNull(resp.building)
        assertEquals(chest.instanceId.toString(), view.instanceId)
        assertEquals("STORAGE_CHEST", view.type)
        assertEquals("ACTIVE", view.status)
        assertEquals(8, view.progressSteps)
        assertEquals(8, view.totalSteps)
        assertEquals("high", view.hpBand)
        assertEquals(nodeId.value, view.nodeId)
        assertEquals(builderId.id.toString(), view.builderAgentId)
        assertEquals(40, view.hpCurrent)
        assertEquals(40, view.hpMax)
        assertEquals(7L, view.lastProgressTick)
        // Non-owner at SHALLOW still has catalog detail gated.
        assertNull(view.skillBars)
        assertNull(view.chestContents)
    }

    @Test
    fun `owner sees catalog detail at any Perception`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST)
        val def = stubChestDef()
        val tool = tool(perception = 0, buildings = listOf(chest), defs = mapOf(BuildingType.STORAGE_CHEST to def))

        val resp = tool.dispatch("building", chest.instanceId.toString(), toolContext)

        val view = assertNotNull(resp.building)
        val bars = assertNotNull(view.skillBars)
        assertEquals(1, bars.size)
        val bar = bars.single()
        assertEquals("CARPENTRY", bar.skill)
        assertEquals(0, bar.requiredSkillLevel)
        assertEquals(8, bar.steps)
        assertEquals("WOOD", bar.materialsPerStep.single().itemId)
        assertEquals(3, bar.materialsPerStep.single().quantity)
        assertEquals(1L, view.builtAtTick)
    }

    @Test
    fun `EXPERT-Perception non-owner sees catalog detail`() {
        val chest = building(builder = builderId, type = BuildingType.STORAGE_CHEST)
        val def = stubChestDef()
        val tool = tool(perception = 90, buildings = listOf(chest), defs = mapOf(BuildingType.STORAGE_CHEST to def))

        val resp = tool.dispatch("building", chest.instanceId.toString(), toolContext)

        val view = assertNotNull(resp.building)
        val bars = assertNotNull(view.skillBars)
        assertEquals(1, bars.size)
        assertEquals("CARPENTRY", bars.single().skill)
        assertEquals(0, bars.single().requiredSkillLevel)
    }

    @Test
    fun `owner sees chest contents regardless of Perception — non-owner never does`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST)
        val contents = StubChestContents().apply {
            add(chest.instanceId, ItemId("WOOD"), 5)
            add(chest.instanceId, ItemId("STONE"), 2)
        }
        val ownerTool = tool(perception = 0, buildings = listOf(chest), chestContents = contents)
        val nonOwnerChest = chest.copy(builtByAgentId = builderId)
        val nonOwnerTool = tool(perception = 90, buildings = listOf(nonOwnerChest), chestContents = contents)

        val ownerResp = ownerTool.dispatch("building", chest.instanceId.toString(), toolContext)
        val nonOwnerResp = nonOwnerTool.dispatch("building", nonOwnerChest.instanceId.toString(), toolContext)

        val ownerContents = assertNotNull(ownerResp.building?.chestContents)
        assertEquals(setOf("WOOD" to 5, "STONE" to 2), ownerContents.map { it.itemId to it.quantity }.toSet())
        assertNull(nonOwnerResp.building?.chestContents, "non-owner must not see chest contents")
    }

    @Test
    fun `owner does NOT see chestContents for non-chest types — only STORAGE_CHEST`() {
        val workbench = building(builder = agentId, type = BuildingType.WORKBENCH)
        val tool = tool(perception = 0, buildings = listOf(workbench))

        val resp = tool.dispatch("building", workbench.instanceId.toString(), toolContext)
        assertNull(resp.building?.chestContents)
    }

    @Test
    fun `unknown building id returns NOT_FOUND`() {
        val tool = tool(perception = 90, buildings = emptyList())

        val resp = tool.dispatch("building", UUID.randomUUID().toString(), toolContext)

        assertEquals("error", resp.kind)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    @Test
    fun `non-UUID building id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 90, buildings = emptyList())

        val resp = tool.dispatch("building", "not-a-uuid", toolContext)

        assertEquals("error", resp.kind)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    @Test
    fun `UNDER_CONSTRUCTION multi-bar building surfaces liveBars with per-bar progress`() {
        val watchtower = building(
            builder = builderId,
            type = BuildingType.WATCHTOWER,
            progress = 7,
            totalSteps = 25,
        )
        val bars = listOf(
            BuildingBar(watchtower.instanceId, SkillId("CARPENTRY"), progressSteps = 7, totalSteps = 15),
            BuildingBar(watchtower.instanceId, SkillId("SURVIVAL"), progressSteps = 0, totalSteps = 10),
        )
        val barsStore = StubBuildingBarsStore(byInstance = mapOf(watchtower.instanceId to bars))
        val tool = tool(perception = 0, buildings = listOf(watchtower), bars = barsStore)

        val resp = tool.dispatch("building", watchtower.instanceId.toString(), toolContext)

        val view = assertNotNull(resp.building)
        assertEquals("UNDER_CONSTRUCTION", view.status)
        val live = assertNotNull(view.liveBars)
        assertEquals(2, live.size)
        val byName = live.associateBy { it.skill }
        assertEquals(7, byName["CARPENTRY"]?.progressSteps)
        assertEquals(15, byName["CARPENTRY"]?.totalSteps)
        assertEquals(0, byName["SURVIVAL"]?.progressSteps)
        assertEquals(10, byName["SURVIVAL"]?.totalSteps)
        assertEquals(setOf(watchtower.instanceId), barsStore.lastBatchedQuery)
    }

    @Test
    fun `ACTIVE building does not call the bars store and returns liveBars null`() {
        val chest = building(builder = builderId, type = BuildingType.STORAGE_CHEST)
        val barsStore = StubBuildingBarsStore()
        val tool = tool(perception = 0, buildings = listOf(chest), bars = barsStore)

        val resp = tool.dispatch("building", chest.instanceId.toString(), toolContext)

        assertEquals("ACTIVE", resp.building?.status)
        assertNull(resp.building?.liveBars)
        assertNull(barsStore.lastBatchedQuery, "ACTIVE buildings must skip the side-table read entirely")
    }

    @Test
    fun `off-node inspector with sight visibility gets liveBars null`() {
        val adjacentNodeId = NodeId(2L)
        val watchtower = building(
            builder = builderId,
            type = BuildingType.WATCHTOWER,
            node = adjacentNodeId,
            progress = 5,
            totalSteps = 25,
        )
        val bars = listOf(
            BuildingBar(watchtower.instanceId, SkillId("CARPENTRY"), progressSteps = 5, totalSteps = 15),
            BuildingBar(watchtower.instanceId, SkillId("SURVIVAL"), progressSteps = 0, totalSteps = 10),
        )
        val barsStore = StubBuildingBarsStore(byInstance = mapOf(watchtower.instanceId to bars))
        val tool = tool(
            perception = 90,
            buildings = listOf(watchtower),
            bars = barsStore,
            within = mapOf((nodeId to 1) to setOf(nodeId, adjacentNodeId)),
        )

        val resp = tool.dispatch("building", watchtower.instanceId.toString(), toolContext)

        assertEquals("UNDER_CONSTRUCTION", resp.building?.status)
        assertNull(resp.building?.liveBars, "off-node inspector must not see per-bar progress")
        assertNull(barsStore.lastBatchedQuery, "off-node case must skip the side-table read")
    }

    @Test
    fun `building outside sight range returns NOT_VISIBLE`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST, node = outOfSightNodeId)
        val tool = tool(perception = 90, buildings = listOf(chest))

        val resp = tool.dispatch("building", chest.instanceId.toString(), toolContext)

        assertEquals("error", resp.kind)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    private fun building(
        builder: AgentId,
        type: BuildingType,
        node: NodeId = nodeId,
        progress: Int = 8,
        totalSteps: Int = 8,
        hp: Int = 40,
    ): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = node,
        type = type,
        status = if (progress == totalSteps) BuildingStatus.ACTIVE else BuildingStatus.UNDER_CONSTRUCTION,
        builtByAgentId = builder,
        builtAtTick = 1L,
        lastProgressTick = 7L,
        progressSteps = progress,
        totalSteps = totalSteps,
        hpCurrent = hp,
        hpMax = hp,
    )

    private fun stubChestDef(): BuildingDefView = BuildingDefView(
        type = BuildingType.STORAGE_CHEST,
        skillBars = listOf(
            dev.gvart.genesara.world.BuildingBarView(
                skill = SkillId("CARPENTRY"),
                level = 0,
                steps = 8,
                materialsPerStep = mapOf(ItemId("WOOD") to 3),
            ),
        ),
        staminaPerStep = 8,
        hp = 40,
        categoryHint = BuildingCategoryHint.STORAGE,
        chestCapacityGrams = 50_000,
    )

    private fun tool(
        perception: Int,
        buildings: List<Building>,
        defs: Map<BuildingType, BuildingDefView> = emptyMap(),
        chestContents: ChestContentsStore = StubChestContents(),
        bars: BuildingBarsStore = StubBuildingBarsStore(),
        within: Map<Pair<NodeId, Int>, Set<NodeId>> = mapOf((nodeId to 1) to setOf(nodeId)),
    ): InspectTool {
        val world = StubQuery(
            location = nodeId,
            nodes = mapOf(
                nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
                outOfSightNodeId to Node(outOfSightNodeId, regionId, q = 99, r = 99, terrain = Terrain.FOREST, adjacency = emptySet()),
            ),
            regions = mapOf(regionId to region),
            within = within,
        )
        return InspectTool(
            world = world,
            agents = registry(caller(perception)),
            vision = StubVision(sight = 1),
            items = StubItems,
            activity = activity,
            tick = FixedTickClock(0L),
            buildings = StubBuildings(buildings),
            buildingDefs = StubBuildingDefs(defs),
            buildingBars = bars,
            chestContents = chestContents,
            equipmentInstances = NoEquipmentInstances,
            equipmentSets = NoEquipmentSets,
            gateStates = NoGates,
        )
    }

    private fun caller(perception: Int) = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "caller",
        attributes = AgentAttributes(perception = perception),
    )

    private fun registry(vararg present: Agent) = object : AgentRegistry {
        private val byId = present.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = present.filter { it.owner == owner }
    }

    private fun InspectTool.dispatch(targetType: String, targetId: String, ctx: org.springframework.ai.chat.model.ToolContext) =
        invoke(InspectTargetType.valueOf(targetType.uppercase()), targetId, ctx)

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private class StubVision(private val sight: Int) : VisionRadius {
        override fun radiusFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<dev.gvart.genesara.world.Building>,
        ): Int = sight
    }

    private object StubItems : ItemLookup {
        override fun byId(id: ItemId): Item? = null
        override fun all(): List<Item> = emptyList()
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
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class StubBuildings(private val rows: List<Building>) : BuildingsLookup {
        override fun byId(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun byNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private class StubBuildingDefs(private val defs: Map<BuildingType, BuildingDefView>) : BuildingDefLookup {
        override fun byType(type: BuildingType): BuildingDefView? = defs[type]
        override fun all(): List<BuildingDefView> = defs.values.toList()
    }

    private class StubBuildingBarsStore(
        private val byInstance: Map<UUID, List<BuildingBar>> = emptyMap(),
    ) : BuildingBarsStore {
        var lastBatchedQuery: Set<UUID>? = null
            private set

        override fun insertAll(bars: List<BuildingBar>) = error("not used")
        override fun barsByInstance(instanceId: UUID): List<BuildingBar> =
            error("inspect must route through barsByInstances")

        override fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<BuildingBar>> {
            lastBatchedQuery = instanceIds
            return byInstance.filterKeys { it in instanceIds }
        }

        override fun advanceBar(instanceId: UUID, skill: SkillId): BuildingBar? = error("not used")
    }

    private class StubChestContents : ChestContentsStore {
        private val map = mutableMapOf<Pair<UUID, ItemId>, Int>()
        override fun quantityOf(buildingId: UUID, item: ItemId): Int = map[buildingId to item] ?: 0
        override fun contentsOf(buildingId: UUID): Map<ItemId, Int> =
            map.entries.filter { it.key.first == buildingId }.associate { it.key.second to it.value }
        override fun add(buildingId: UUID, item: ItemId, quantity: Int) {
            map.merge(buildingId to item, quantity, Int::plus)
        }
        override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean = error("not used")
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class FixedTickClock(private val current: Long) : TickClock {
        override fun currentTick(): Long = current
    }

    private object NoEquipmentInstances : EquipmentInstanceStore {
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = null
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = emptyList()
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private object NoEquipmentSets : EquipmentSetLookup {
        override fun byId(id: EquipmentSetId): EquipmentSet? = null
        override fun all(): List<EquipmentSet> = emptyList()
        override fun setsContaining(itemId: ItemId): List<EquipmentSet> = emptyList()
    }

    private object NoGates : dev.gvart.genesara.world.BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: java.util.UUID) = error("not used")
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = null
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = null
    }
}
