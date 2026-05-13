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
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquipmentSetThreshold
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Rarity
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
        val resp = tool.invoke(InspectTargetType.NODE, "   ", toolContext)
        assertEquals("error", resp.kind)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    // --- node ---

    @Test
    fun `inspect current node at SHALLOW Perception returns terrain + ids only`() {
        val tool = tool(perception = 1)
        val resp = tool.dispatch("node", currentNodeId.value.toString(), toolContext)

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
        val resp = tool.dispatch("node", adjacentNodeId.value.toString(), toolContext)

        assertEquals("node", resp.kind)
        assertNull(resp.node?.resourceQuantities, "adjacent node at SHALLOW must not leak quantities")
    }

    @Test
    fun `inspect adjacent node at DETAILED Perception reveals quantities`() {
        val tool = tool(perception = 8)
        val resp = tool.dispatch("node", adjacentNodeId.value.toString(), toolContext)

        val node = assertNotNull(resp.node)
        assertNotNull(node.resourceQuantities, "DETAILED Perception should expose quantities even on adjacent tiles")
        assertNull(node.expert, "EXPERT view is gated to perception >= 15")
    }

    @Test
    fun `inspect adjacent node at EXPERT Perception exposes pvpEnabled`() {
        val tool = tool(perception = 20)
        val resp = tool.dispatch("node", adjacentNodeId.value.toString(), toolContext)

        val expert = assertNotNull(resp.node?.expert)
        assertEquals(false, expert.pvpEnabled, "adjacent node was constructed as a green zone")
    }

    @Test
    fun `inspect node outside sight is rejected with NOT_VISIBLE`() {
        val tool = tool(perception = 50)
        val resp = tool.dispatch("node", outOfSightNodeId.value.toString(), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect non-numeric node id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 5)
        val resp = tool.dispatch("node", "abc", toolContext)
        assertEquals(InspectError.BAD_TARGET_ID, resp.error?.code)
    }

    @Test
    fun `inspect missing node returns NOT_FOUND`() {
        val tool = tool(perception = 5)
        val resp = tool.dispatch("node", "12345", toolContext)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    // --- agent ---

    @Test
    fun `inspect agent in same node returns banded body at DETAILED`() {
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = body(hp = 50, maxHp = 100))
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)

        assertEquals("agent", resp.kind)
        val view = assertNotNull(resp.agent)
        assertEquals("wanderer", view.name)
        assertEquals(7, view.level)
        assertEquals("mid", view.hpBand, "50/100 should band as 'mid'")
        // No exact HP exposed.
        assertNull(view.activeEffects, "EXPERT-only field should be null at DETAILED")
    }

    @Test
    fun `inspect agent at SHALLOW Perception still surfaces class and bands — parity with look_around`() {
        val tool = tool(perception = 1, otherAgentNode = currentNodeId, body = body(hp = 50, maxHp = 100))
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)

        val view = assertNotNull(resp.agent)
        assertEquals("SCOUT", view.classId)
        assertEquals("mid", view.hpBand)
        assertEquals("mid", view.staminaBand)
        assertNull(view.activeEffects, "EXPERT-only field still gated")
    }

    @Test
    fun `inspect non-psionic agent never exposes a mana band`() {
        val tool = tool(perception = 50, otherAgentNode = currentNodeId, body = body(hp = 80, maxHp = 100, maxMana = 0))
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)

        assertNull(resp.agent?.manaBand, "non-psionic agents have null mana per canon")
    }

    @Test
    fun `inspect agent in different node returns NOT_VISIBLE`() {
        val tool = tool(perception = 10, otherAgentNode = adjacentNodeId)
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect offline agent returns NOT_VISIBLE`() {
        val tool = tool(perception = 10, otherAgentNode = null)
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)
        assertEquals(InspectError.NOT_VISIBLE, resp.error?.code)
    }

    @Test
    fun `inspect non-UUID agent id returns BAD_TARGET_ID`() {
        val tool = tool(perception = 5)
        val resp = tool.dispatch("agent", "not-a-uuid", toolContext)
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
            equipmentInstances = StubEquipmentInstanceStore(),
            equipmentSets = StubEquipmentSetLookup(),
        )

        val resp = selfTool.dispatch("agent", agentId.id.toString(), toolContext)

        val view = assertNotNull(resp.agent)
        assertEquals("high", view.hpBand, "90/100 should band as 'high', not the exact 90")
    }

    @Test
    fun `inspect psionic agent at DETAILED Perception exposes a manaBand`() {
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = body(hp = 80, maxHp = 100, maxMana = 50))
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)

        assertEquals("mid", resp.agent?.manaBand, "psionic agents (maxMana > 0) get a banded mana view")
    }

    @Test
    fun `inspect agent who passed presence but has no body row returns NOT_FOUND`() {
        // State inconsistency guard: agent has an active position row but no body row.
        // The tool surfaces it as NOT_FOUND so a caller has something to react to.
        val tool = tool(perception = 10, otherAgentNode = currentNodeId, body = null)
        val resp = tool.dispatch("agent", otherAgentId.id.toString(), toolContext)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    // --- item ---

    @Test
    fun `inspect item in inventory returns shallow view at low Perception`() {
        val tool = tool(perception = 1, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)

        assertEquals("item", resp.kind)
        val view = assertNotNull(resp.item)
        assertEquals(5, view.quantity)
        assertNull(view.weightPerUnit, "weight is DETAILED+")
        assertNull(view.harvestSkill, "harvestSkill is EXPERT-only")
    }

    @Test
    fun `inspect item at EXPERT exposes harvestSkill`() {
        val tool = tool(perception = 20, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)
        assertEquals("FORESTRY", resp.item?.harvestSkill)
    }

    @Test
    fun `inspect item at EXPERT also surfaces weight, stack, and regenerating flag`() {
        val tool = tool(perception = 20, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)

        val view = assertNotNull(resp.item)
        assertEquals(200, view.weightPerUnit)
        assertEquals(99, view.maxStack)
        assertEquals(true, view.regenerating)
    }

    @Test
    fun `inspect item at SHALLOW hides catalog rarity and maxDurability`() {
        val tool = tool(perception = 1, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)

        val view = assertNotNull(resp.item)
        assertNull(view.rarity, "rarity is DETAILED+")
        assertNull(view.maxDurability, "maxDurability is DETAILED+")
    }

    @Test
    fun `inspect item at DETAILED exposes catalog rarity (defaults to COMMON for stackable resources)`() {
        val tool = tool(perception = 10, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)

        val view = assertNotNull(resp.item)
        assertEquals("COMMON", view.rarity)
        // Stackable resources have no durability concept — null even at DETAILED.
        assertNull(view.maxDurability)
    }

    @Test
    fun `inspect item not in inventory returns NOT_IN_INVENTORY`() {
        val tool = tool(perception = 5, inventory = emptyList())
        val resp = tool.dispatch("item", "WOOD", toolContext)
        assertEquals(InspectError.NOT_IN_INVENTORY, resp.error?.code)
    }

    @Test
    fun `inspect unknown item returns NOT_FOUND`() {
        val tool = tool(perception = 5, inventory = listOf(InventoryEntry(ItemId("ZILCH"), 1)))
        val resp = tool.dispatch("item", "ZILCH", toolContext)
        // Unknown to the catalog -> NOT_FOUND, even if the agent has a stack of it (which
        // shouldn't happen in practice but we guard against catalog drift).
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    @Test
    fun `inspect stackable resource returns no equipmentStats, instanceState, or equipmentSets`() {
        val tool = tool(perception = 10, inventory = listOf(InventoryEntry(ItemId("WOOD"), 5)))
        val resp = tool.dispatch("item", "WOOD", toolContext)

        val view = assertNotNull(resp.item)
        assertNull(view.equipmentStats)
        assertNull(view.instanceState)
        assertNull(view.equipmentSets, "stackable resources have no set membership concept")
    }

    @Test
    fun `inspect equipment instance projects catalog stats, per-instance state, and set ids`() {
        val instanceId = UUID.randomUUID()
        val creator = AgentId(UUID.randomUUID())
        val instance = EquipmentInstance(
            instanceId = instanceId,
            agentId = agentId,
            itemId = ItemId("IRON_CHESTPLATE"),
            rarity = Rarity.RARE,
            durabilityCurrent = 73,
            durabilityMax = 100,
            creatorAgentId = creator,
            createdAtTick = 1L,
        )
        val ironSet = EquipmentSet(
            id = EquipmentSetId("IRON"),
            pieces = setOf(ItemId("IRON_CHESTPLATE")),
            thresholds = mapOf(
                1 to EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.AttributeBonus(Attribute.STRENGTH, 1)),
                ),
            ),
        )
        val tool = tool(
            perception = 10,
            equipmentInstances = StubEquipmentInstanceStore(listOf(instance)),
            equipmentSets = StubEquipmentSetLookup(listOf(ironSet)),
        )

        val resp = tool.dispatch("item", instanceId.toString(), toolContext)

        val view = assertNotNull(resp.item)
        assertEquals("IRON_CHESTPLATE", view.itemId)
        assertEquals(1, view.quantity, "an instance is one physical item")

        val stats = assertNotNull(view.equipmentStats)
        assertEquals(listOf("CHEST"), stats.slots)
        assertEquals(false, stats.twoHanded)
        assertEquals(100, stats.maxDurability)
        assertEquals(2, stats.bonuses.size)

        val state = assertNotNull(view.instanceState)
        assertEquals("RARE", state.rarity)
        assertEquals(73, state.durabilityCurrent)
        assertEquals(100, state.durabilityMax)
        assertEquals(creator.id.toString(), state.creator)

        assertEquals(listOf("IRON"), view.equipmentSets)
    }

    @Test
    fun `inspect equipment instance not in any set returns empty equipmentSets`() {
        val instanceId = UUID.randomUUID()
        val instance = EquipmentInstance(
            instanceId = instanceId,
            agentId = agentId,
            itemId = ItemId("PLAIN_RING"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 50,
            creatorAgentId = null,
            createdAtTick = 1L,
        )
        val tool = tool(
            perception = 10,
            equipmentInstances = StubEquipmentInstanceStore(listOf(instance)),
        )

        val resp = tool.dispatch("item", instanceId.toString(), toolContext)

        val view = assertNotNull(resp.item)
        assertEquals(emptyList(), view.equipmentSets, "EQUIPMENT with no set membership returns an empty list, not null")
        assertNull(view.instanceState?.creator, "loot drops carry no creator signature")
    }

    @Test
    fun `inspect equipment instance at SHALLOW Perception hides instanceState and equipmentStats`() {
        val instanceId = UUID.randomUUID()
        val instance = EquipmentInstance(
            instanceId = instanceId,
            agentId = agentId,
            itemId = ItemId("IRON_CHESTPLATE"),
            rarity = Rarity.UNCOMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
        )
        val tool = tool(
            perception = 1,
            equipmentInstances = StubEquipmentInstanceStore(listOf(instance)),
        )

        val resp = tool.dispatch("item", instanceId.toString(), toolContext)

        val view = assertNotNull(resp.item)
        assertNull(view.equipmentStats)
        assertNull(view.instanceState)
        assertNull(view.equipmentSets)
    }

    @Test
    fun `inspect unknown equipment instance UUID returns NOT_FOUND`() {
        val tool = tool(perception = 10)
        val resp = tool.dispatch("item", UUID.randomUUID().toString(), toolContext)
        assertEquals(InspectError.NOT_FOUND, resp.error?.code)
    }

    @Test
    fun `inspect another agent's equipment instance returns NOT_IN_INVENTORY`() {
        val instanceId = UUID.randomUUID()
        val instance = EquipmentInstance(
            instanceId = instanceId,
            agentId = otherAgentId,
            itemId = ItemId("IRON_CHESTPLATE"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
        )
        val tool = tool(
            perception = 10,
            equipmentInstances = StubEquipmentInstanceStore(listOf(instance)),
        )

        val resp = tool.dispatch("item", instanceId.toString(), toolContext)

        assertEquals(InspectError.NOT_IN_INVENTORY, resp.error?.code)
    }

    @Test
    fun `inspect equipment via item id returns catalog stats but no instanceState`() {
        val tool = tool(
            perception = 10,
            inventory = listOf(InventoryEntry(ItemId("IRON_CHESTPLATE"), 1)),
        )

        val resp = tool.dispatch("item", "IRON_CHESTPLATE", toolContext)

        val view = assertNotNull(resp.item)
        assertNotNull(view.equipmentStats, "item-id path should still surface catalog equipment stats")
        assertNull(view.instanceState, "instance state requires the UUID lookup path")
    }

    // --- presence ---

    @Test
    fun `every invocation touches the activity registry`() {
        val tool = tool(perception = 5)
        tool.dispatch("node", currentNodeId.value.toString(), toolContext)
        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    // --- helpers ---

    private fun tool(
        perception: Int,
        otherAgentNode: NodeId? = null,
        body: BodyView? = null,
        inventory: List<InventoryEntry> = emptyList(),
        equipmentInstances: EquipmentInstanceStore = StubEquipmentInstanceStore(),
        equipmentSets: EquipmentSetLookup = StubEquipmentSetLookup(),
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
            equipmentInstances = equipmentInstances,
            equipmentSets = equipmentSets,
        )
    }

    private fun registry(vararg present: Agent) = object : AgentRegistry {
        private val byId = present.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = present.filter { it.owner == owner }
    }

    private class StubVision(private val sight: Int) : VisionRadius {
        override fun radiusFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<dev.gvart.genesara.world.Building>,
        ): Int = sight
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
                harvestSkill = SkillId("FORESTRY"),
            ),
            "IRON_CHESTPLATE" to Item(
                id = ItemId("IRON_CHESTPLATE"),
                displayName = "Iron Chestplate",
                description = "Forged iron breastplate.",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 8000,
                maxStack = 1,
                rarity = Rarity.COMMON,
                maxDurability = 100,
                validSlots = setOf(EquipSlot.CHEST),
                requiredAttributes = mapOf(Attribute.STRENGTH to 6),
                bonuses = listOf(
                    EquippedBonus.ArmorDef(DamageType.SLASH, 12),
                    EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 2),
                ),
            ),
            "PLAIN_RING" to Item(
                id = ItemId("PLAIN_RING"),
                displayName = "Plain Ring",
                description = "Unaffiliated trinket.",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 50,
                maxStack = 1,
                rarity = Rarity.COMMON,
                maxDurability = 50,
                validSlots = setOf(EquipSlot.RING_LEFT, EquipSlot.RING_RIGHT),
            ),
        )
        override fun byId(id: ItemId): Item? = catalog[id.value]
        override fun all(): List<Item> = catalog.values.toList()
    }

    private class StubEquipmentInstanceStore(
        private val instances: List<EquipmentInstance> = emptyList(),
    ) : EquipmentInstanceStore {
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? =
            instances.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
            instances.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            instances.filter { it.agentId == agentId && it.equippedInSlot != null }
                .associateBy { it.equippedInSlot!! }
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class StubEquipmentSetLookup(
        private val sets: List<EquipmentSet> = emptyList(),
    ) : EquipmentSetLookup {
        override fun byId(id: EquipmentSetId): EquipmentSet? = sets.firstOrNull { it.id == id }
        override fun all(): List<EquipmentSet> = sets
        override fun setsContaining(itemId: ItemId): List<EquipmentSet> =
            sets.filter { itemId in it.pieces }
    }

    private fun InspectTool.dispatch(targetType: String, targetId: String, ctx: org.springframework.ai.chat.model.ToolContext) =
        invoke(InspectTargetType.valueOf(targetType.uppercase()), targetId, ctx)

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
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
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
