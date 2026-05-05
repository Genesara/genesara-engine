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
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
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

class InspectToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val otherAgentId = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val worldId = WorldId(1L)
    private val currentNodeId = NodeId(1L)
    private val adjacentNodeId = NodeId(2L)
    private val outOfSightNodeId = NodeId(99L)

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
    private val current = Node(currentNodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(adjacentNodeId))
    private val adjacent = Node(adjacentNodeId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(currentNodeId), pvpEnabled = false)
    private val outOfSight = Node(outOfSightNodeId, regionId, q = 5, r = 5, terrain = Terrain.MOUNTAIN, adjacency = emptySet())

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    private fun caller(perception: Int) = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "scout",
        classId = AgentClass.SCOUT,
        attributes = AgentAttributes(perception = perception),
    )

    private val targetHumanoid = Agent(
        id = otherAgentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "wanderer",
        classId = AgentClass.SCOUT,
        race = RaceId("HUMAN_NORTHERN"),
        level = 7,
    )

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agentId)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    // --- request validation ---

    @Test
    fun `blank targetId returns BAD_TARGET_ID`() {
        val tool = tool(perception = 5)
        val resp = tool.invoke(InspectRequest(targetType = InspectTargetType.NODE, targetId = "   "), toolContext)
        assertEquals("error", resp.kind)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    // --- node ---

    @Test
    fun `inspect current node at SHALLOW Perception returns terrain + ids only`() {
        val tool = tool(perception = 1)
        val resp = tool.invoke(req("node", currentNodeId.value.toString()), toolContext)

        assertEquals("node", resp.kind)
        assertEquals("shallow", resp.depth)
        val view = assertNotNull(resp.node)
        assertEquals(Terrain.FOREST.name, view.terrain)
        // Current node always carries quantities, even at SHALLOW Perception (matches look_around).
        assertNotNull(view.resourceQuantities)
        assertNull(view.expert)
    }

    @Test
    fun `inspect adjacent node at SHALLOW Perception hides quantities`() {
        val tool = tool(perception = 1)
        val resp = tool.invoke(req("node", adjacentNodeId.value.toString()), toolContext)

        assertEquals("node", resp.kind)
        assertNull(resp.node?.resourceQuantities, "adjacent node at SHALLOW must not leak quantities")
    }

    @Test
    fun `inspect adjacent node at DETAILED Perception reveals quantities`() {
        val tool = tool(perception = 8)
        val resp = tool.invoke(req("node", adjacentNodeId.value.toString()), toolContext)

        val node = assertNotNull(resp.node)
        assertNotNull(node.resourceQuantities, "DETAILED Perception should expose quantities even on adjacent tiles")
        assertNull(node.expert, "EXPERT view is gated to perception >= 15")
    }

    @Test
    fun `inspect adjacent node at EXPERT Perception exposes pvpEnabled`() {
        val tool = tool(perception = 20)
        val resp = tool.invoke(req("node", adjacentNodeId.value.toString()), toolContext)

        val expert = assertNotNull(resp.node?.expert)
        assertEquals(false, expert.pvpEnabled, "adjacent node was constructed as a green zone")
    }

    @Test
    fun `inspect node outside sight is rejected with NOT_VISIBLE`() {
        val tool = tool(perception = 50)
        val resp = tool.invoke(req("node", outOfSightNodeId.value.toString()), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect non-numeric node id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 5)
        val resp = tool.invoke(req("node", "abc"), toolContext)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    @Test
    fun `inspect missing node returns NOT_FOUND`() {
        val tool = tool(perception = 5)
        val resp = tool.invoke(req("node", "12345"), toolContext)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    // --- agent ---

    @Test
    fun `inspect agent in same node returns banded body at DETAILED`() {
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = body(hp = 50, maxHp = 100))
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)

        assertEquals("agent", resp.kind)
        val view = assertNotNull(resp.agent)
        assertEquals("wanderer", view.name)
        assertEquals(7, view.level)
        assertEquals("mid", view.hpBand, "50/100 should band as 'mid'")
        // No exact HP exposed.
        assertNull(view.activeEffects, "EXPERT-only field should be null at DETAILED")
    }

    @Test
    fun `inspect agent at SHALLOW Perception hides class and bands`() {
        val tool = tool(perception = 1, otherAgentNode = currentNodeId, body = body(hp = 50, maxHp = 100))
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)

        val view = assertNotNull(resp.agent)
        assertNull(view.classId)
        assertNull(view.hpBand)
        assertNull(view.staminaBand)
    }

    @Test
    fun `inspect non-psionic agent never exposes a mana band`() {
        val tool = tool(perception = 50, otherAgentNode = currentNodeId, body = body(hp = 80, maxHp = 100, maxMana = 0))
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)

        assertNull(resp.agent?.manaBand, "non-psionic agents have null mana per canon")
    }

    @Test
    fun `inspect agent in different node returns NOT_VISIBLE`() {
        val tool = tool(perception = 10, otherAgentNode = adjacentNodeId)
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect offline agent returns NOT_VISIBLE`() {
        val tool = tool(perception = 10, otherAgentNode = null)
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect non-UUID agent id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 5)
        val resp = tool.invoke(req("agent", "not-a-uuid"), toolContext)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    @Test
    fun `inspect self returns banded vitals — get_status is the precise-numbers endpoint`() {
        // Self-inspection follows the same banded-vitals contract as inspecting another
        // agent. Agents who want exact HP/Stamina should call get_status. This pins the
        // contract documented on AgentInspectView.
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = body(hp = 100, maxHp = 100))
        // Override registry so the caller's id == target id and a body is returned for it.
        val selfBody = body(hp = 90, maxHp = 100)
        val world = StubQuery(
            location = currentNodeId,
            otherLocation = currentNodeId,
            otherAgentId = agentId,
            nodes = mapOf(currentNodeId to current),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId)),
            inventory = emptyList(),
            body = selfBody,
        )
        val selfTool = InspectTool(
            world = world,
            agents = registry(caller(perception = 10)),
            vision = StubVision(sight = 1),
            items = StubItems,
            activity = activity,
            tick = FixedTickClock(0L),
            buildings = NoBuildings,
            buildingDefs = NoBuildingDefs,
            chestContents = NoChestContents,
        )

        val resp = selfTool.invoke(req("agent", agentId.id.toString()), toolContext)

        val view = assertNotNull(resp.agent)
        assertEquals("high", view.hpBand, "90/100 should band as 'high', not the exact 90")
    }

    @Test
    fun `inspect psionic agent at DETAILED Perception exposes a manaBand`() {
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = body(hp = 80, maxHp = 100, maxMana = 50))
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)

        assertEquals("mid", resp.agent?.manaBand, "psionic agents (maxMana > 0) get a banded mana view")
    }

    @Test
    fun `inspect agent who passed presence but has no body row returns NOT_FOUND`() {
        // State inconsistency guard: agent has an active position row but no body row.
        // The tool surfaces it as NOT_FOUND so a caller has something to react to.
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = null)
        val resp = tool.invoke(req("agent", otherAgentId.id.toString()), toolContext)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    // --- item ---

    @Test
    fun `inspect item in inventory returns shallow view at low Perception`() {
        val tool = tool(perception = 1, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.invoke(req("item", "WOOD"), toolContext)

        assertEquals("item", resp.kind)
        val view = assertNotNull(resp.item)
        assertEquals(5, view.quantity)
        assertNull(view.weightPerUnit, "weight is DETAILED+")
        assertNull(view.harvestSkill, "harvestSkill is EXPERT-only")
    }

    @Test
    fun `inspect item at EXPERT exposes harvestSkill`() {
        val tool = tool(perception = 20, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.invoke(req("item", "WOOD"), toolContext)
        assertEquals("FORESTRY", resp.item?.harvestSkill)
    }

    @Test
    fun `inspect item at EXPERT also surfaces weight, stack, and regenerating flag`() {
        val tool = tool(perception = 20, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.invoke(req("item", "WOOD"), toolContext)

        val view = assertNotNull(resp.item)
        assertEquals(200, view.weightPerUnit)
        assertEquals(99, view.maxStack)
        assertEquals(true, view.regenerating)
    }

    @Test
    fun `inspect item at SHALLOW hides catalog rarity and maxDurability`() {
        val tool = tool(perception = 1, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.invoke(req("item", "WOOD"), toolContext)

        val view = assertNotNull(resp.item)
        assertNull(view.rarity, "rarity is DETAILED+")
        assertNull(view.maxDurability, "maxDurability is DETAILED+")
    }

    @Test
    fun `inspect item at DETAILED exposes catalog rarity (defaults to COMMON for stackable resources)`() {
        val tool = tool(perception = 10, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.invoke(req("item", "WOOD"), toolContext)

        val view = assertNotNull(resp.item)
        assertEquals("COMMON", view.rarity)
        // Stackable resources have no durability concept — null even at DETAILED.
        assertNull(view.maxDurability)
    }

    @Test
    fun `inspect item not in inventory returns NOT_IN_INVENTORY`() {
        val tool = tool(perception = 5, inventory = emptyList())
        val resp = tool.invoke(req("item", "WOOD"), toolContext)
        assertEquals(InspectError.NOT_IN_INVENTORY, resp.error?.code)
    }

    @Test
    fun `inspect unknown item returns NOT_FOUND`() {
        val tool = tool(perception = 5, inventory = listOf(InventoryEntry(ItemId("ZILCH"), 1)))
        val resp = tool.invoke(req("item", "ZILCH"), toolContext)
        // Unknown to the catalog -> NOT_FOUND, even if the agent has a stack of it (which
        // shouldn't happen in practice but we guard against catalog drift).
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    // --- presence ---

    @Test
    fun `every invocation touches the activity registry`() {
        val tool = tool(perception = 5)
        tool.invoke(req("node", currentNodeId.value.toString()), toolContext)
        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    // --- helpers ---

    private fun tool(
        perception: Int,
        otherAgentNode: NodeId? = null,
        body: BodyView? = null,
        inventory: List<InventoryEntry> = emptyList(),
    ): InspectTool {
        val world = StubQuery(
            location = currentNodeId,
            otherLocation = otherAgentNode,
            otherAgentId = otherAgentId,
            nodes = mapOf(currentNodeId to current, adjacentNodeId to adjacent, outOfSightNodeId to outOfSight),
            regions = mapOf(regionId to region),
            within = mapOf((currentNodeId to 1) to setOf(currentNodeId, adjacentNodeId)),
            inventory = inventory,
            body = body,
        )
        return InspectTool(
            world = world,
            agents = registry(caller(perception), targetHumanoid),
            vision = StubVision(sight = 1),
            items = StubItems,
            activity = activity,
            tick = FixedTickClock(0L),
            buildings = NoBuildings,
            buildingDefs = NoBuildingDefs,
            chestContents = NoChestContents,
        )
    }

    private fun registry(vararg present: Agent) = object : AgentRegistry {
        private val byId = present.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = present.filter { it.owner == owner }
    }

    private class StubVision(private val sight: Int) : VisionRadius {
        override fun radiusFor(agent: Agent, currentNode: NodeId): Int = sight
    }

    private object StubItems : ItemLookup {
        private val catalog = mapOf(
            "WOOD" to Item(
                id = ItemId("WOOD"),
                displayName = "Wood",
                description = "Raw timber.",
                category = ItemCategory.RESOURCE,
                weightPerUnit = 200,
                maxStack = 99,
                harvestSkill = "FORESTRY",
            ),
        )
        override fun byId(id: ItemId): Item? = catalog[id.value]
        override fun all(): List<Item> = catalog.values.toList()
    }

    private fun req(targetType: String, targetId: String) =
        InspectRequest(targetType = InspectTargetType.valueOf(targetType.uppercase()), targetId = targetId)

    private fun body(hp: Int, maxHp: Int, maxMana: Int = 0) = BodyView(
        hp = hp, maxHp = maxHp,
        stamina = 50, maxStamina = 100,
        mana = if (maxMana > 0) maxMana / 2 else 0, maxMana = maxMana,
        hunger = 100, maxHunger = 100,
        thirst = 100, maxThirst = 100,
        sleep = 100, maxSleep = 100,
    )

    private class StubQuery(
        private val location: NodeId?,
        private val otherLocation: NodeId?,
        private val otherAgentId: AgentId,
        private val nodes: Map<NodeId, Node>,
        private val regions: Map<RegionId, Region>,
        private val within: Map<Pair<NodeId, Int>, Set<NodeId>>,
        private val inventory: List<InventoryEntry>,
        private val body: BodyView?,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? =
            if (agent == otherAgentId) otherLocation else location
        override fun activePositionOf(agent: AgentId): NodeId? =
            if (agent == otherAgentId) otherLocation else location
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> =
            within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = if (agent == otherAgentId) body else null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(inventory)
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class FixedTickClock(private val current: Long) : TickClock {
        override fun currentTick(): Long = current
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

    internal object NoBuildingDefs : dev.gvart.genesara.world.BuildingDefLookup {
        override fun byType(type: dev.gvart.genesara.world.BuildingType): dev.gvart.genesara.world.BuildingDefView? = null
        override fun all(): List<dev.gvart.genesara.world.BuildingDefView> = emptyList()
    }

    internal object NoChestContents : dev.gvart.genesara.world.ChestContentsStore {
        override fun quantityOf(buildingId: java.util.UUID, item: ItemId): Int = 0
        override fun contentsOf(buildingId: java.util.UUID): Map<ItemId, Int> = emptyMap()
        override fun add(buildingId: java.util.UUID, item: ItemId, quantity: Int) = error("not used")
        override fun remove(buildingId: java.util.UUID, item: ItemId, quantity: Int): Boolean = error("not used")
    }
}
