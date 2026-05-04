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
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ChestContentsStore
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
    fun `SHALLOW perception surfaces type, status, progress, hpBand only`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST)
        val tool = tool(perception = 0, buildings = listOf(chest))

        val resp = tool.invoke(req("building", chest.instanceId.toString()), toolContext)

        val view = assertNotNull(resp.building)
        assertEquals(chest.instanceId.toString(), view.instanceId)
        assertEquals("STORAGE_CHEST", view.type)
        assertEquals("ACTIVE", view.status)
        assertEquals(8, view.progressSteps)
        assertEquals(8, view.totalSteps)
        assertEquals("high", view.hpBand)
        assertNull(view.nodeId)
        assertNull(view.builderAgentId)
        assertNull(view.hpCurrent)
        assertNull(view.totalMaterials)
        assertNull(view.chestContents)
    }

    @Test
    fun `DETAILED perception adds nodeId, builderAgentId, exact hp, lastProgressTick`() {
        val chest = building(builder = builderId, type = BuildingType.STORAGE_CHEST)
        val tool = tool(perception = 50, buildings = listOf(chest))

        val resp = tool.invoke(req("building", chest.instanceId.toString()), toolContext)

        val view = assertNotNull(resp.building)
        assertEquals(nodeId.value, view.nodeId)
        assertEquals(builderId.id.toString(), view.builderAgentId)
        assertEquals(40, view.hpCurrent)
        assertEquals(40, view.hpMax)
        assertEquals(7L, view.lastProgressTick)
        // EXPERT-only fields stay null at DETAILED.
        assertNull(view.totalMaterials)
        assertNull(view.requiredSkill)
        assertNull(view.chestContents)
    }

    @Test
    fun `EXPERT perception adds catalog materials breakdown and required skill`() {
        val chest = building(builder = builderId, type = BuildingType.STORAGE_CHEST)
        val def = stubChestDef()
        val tool = tool(perception = 90, buildings = listOf(chest), defs = mapOf(BuildingType.STORAGE_CHEST to def))

        val resp = tool.invoke(req("building", chest.instanceId.toString()), toolContext)

        val view = assertNotNull(resp.building)
        assertEquals("CARPENTRY", view.requiredSkill)
        assertEquals(0, view.requiredSkillLevel)
        val totals = assertNotNull(view.totalMaterials)
        assertEquals(1, totals.size)
        assertEquals("WOOD", totals.single().itemId)
        assertEquals(20, totals.single().quantity)
        val steps = assertNotNull(view.stepMaterials)
        assertEquals(8, steps.size)
    }

    @Test
    fun `EXPERT-and-owner sees chest contents — non-owner does not`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST)
        val contents = StubChestContents().apply {
            add(chest.instanceId, ItemId("WOOD"), 5)
            add(chest.instanceId, ItemId("STONE"), 2)
        }
        val ownerTool = tool(perception = 90, buildings = listOf(chest), chestContents = contents)
        val nonOwnerChest = chest.copy(builtByAgentId = builderId)
        val nonOwnerTool = tool(perception = 90, buildings = listOf(nonOwnerChest), chestContents = contents)

        val ownerResp = ownerTool.invoke(req("building", chest.instanceId.toString()), toolContext)
        val nonOwnerResp = nonOwnerTool.invoke(req("building", nonOwnerChest.instanceId.toString()), toolContext)

        val ownerContents = assertNotNull(ownerResp.building?.chestContents)
        assertEquals(setOf("WOOD" to 5, "STONE" to 2), ownerContents.map { it.itemId to it.quantity }.toSet())
        assertNull(nonOwnerResp.building?.chestContents, "non-owner must not see chest contents")
    }

    @Test
    fun `EXPERT does NOT surface chestContents for non-chest types — only STORAGE_CHEST`() {
        val workbench = building(builder = agentId, type = BuildingType.WORKBENCH)
        val tool = tool(perception = 90, buildings = listOf(workbench))

        val resp = tool.invoke(req("building", workbench.instanceId.toString()), toolContext)
        assertNull(resp.building?.chestContents)
    }

    @Test
    fun `unknown building id returns NOT_FOUND`() {
        val tool = tool(perception = 90, buildings = emptyList())

        val resp = tool.invoke(req("building", UUID.randomUUID().toString()), toolContext)

        assertEquals("error", resp.kind)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    @Test
    fun `non-UUID building id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 90, buildings = emptyList())

        val resp = tool.invoke(req("building", "not-a-uuid"), toolContext)

        assertEquals("error", resp.kind)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    @Test
    fun `building outside sight range returns NOT_VISIBLE`() {
        val chest = building(builder = agentId, type = BuildingType.STORAGE_CHEST, node = outOfSightNodeId)
        val tool = tool(perception = 90, buildings = listOf(chest))

        val resp = tool.invoke(req("building", chest.instanceId.toString()), toolContext)

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
        totalMaterials = mapOf(ItemId("WOOD") to 20),
        stepMaterials = (1..8).map { mapOf(ItemId("WOOD") to (if (it == 8) 6 else 2)) },
        requiredSkill = SkillId("CARPENTRY"),
        requiredSkillLevel = 0,
        totalSteps = 8,
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
    ): InspectTool {
        val world = StubQuery(
            location = nodeId,
            nodes = mapOf(
                nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
                outOfSightNodeId to Node(outOfSightNodeId, regionId, q = 99, r = 99, terrain = Terrain.FOREST, adjacency = emptySet()),
            ),
            regions = mapOf(regionId to region),
            within = mapOf((nodeId to 1) to setOf(nodeId)),
        )
        return InspectTool(
            world = world,
            agents = registry(caller(perception)),
            classes = StubClasses(sight = 1),
            items = StubItems,
            activity = activity,
            tick = FixedTickClock(0L),
            buildings = StubBuildings(buildings),
            buildingDefs = StubBuildingDefs(defs),
            chestContents = chestContents,
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

    private fun req(targetType: String, targetId: String) = InspectRequest(targetType, targetId)

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private class StubClasses(private val sight: Int) : ClassPropertiesLookup {
        override fun sightRange(classId: AgentClass?): Int = sight
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
}
