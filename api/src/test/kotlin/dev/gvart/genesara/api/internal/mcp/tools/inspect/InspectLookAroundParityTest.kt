package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.LookAroundTool
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.RecalledNode
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

class InspectLookAroundParityTest {

    private val callerId = AgentId(UUID.randomUUID())
    private val otherId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000aaa"))
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.FOREST,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    private val caller = Agent(
        id = callerId,
        owner = PlayerId(UUID.randomUUID()),
        name = "caller",
        attributes = AgentAttributes(perception = 1),
    )
    private val other = Agent(
        id = otherId,
        owner = PlayerId(UUID.randomUUID()),
        name = "wanderer",
        classId = AgentClass.SCOUT,
        race = RaceId("human_warden"),
        level = 4,
    )

    @BeforeEach fun setUp() = AgentContextHolder.set(callerId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `inspect AGENT and look_around agree on hpBand for the same target at the same tick`() {
        val body = BodyView(
            hp = 80, maxHp = 100,
            stamina = 30, maxStamina = 100,
            mana = 0, maxMana = 0,
            hunger = 50, maxHunger = 100,
            thirst = 50, maxThirst = 100,
            sleep = 50, maxSleep = 100,
        )
        val world = SharedWorld(
            location = nodeId,
            nodes = mapOf(nodeId to node),
            regions = mapOf(regionId to region),
            within = mapOf((nodeId to 1) to setOf(nodeId)),
            occupants = mapOf(nodeId to listOf(callerId, otherId)),
            bodies = mapOf(callerId to body, otherId to body),
        )
        val registry = registryOf(caller, other)
        val inspect = inspectTool(world, registry)
        val lookAround = LookAroundTool(world, registry, vision(1), activity, NoMapMemory, NoBuildings, NoOpPlots, NoOpCrops)

        val inspectBand = assertNotNull(inspect.dispatch("agent", otherId.id.toString())).agent?.hpBand
        val lookBand = lookAround.invoke(toolContext)
            .currentNode.agents.single { it.id == otherId.id.toString() }.hpBand

        assertEquals(lookBand, inspectBand, "inspect must return the same hpBand as look_around for same-node agents")
    }

    @Test
    fun `inspect BUILDING and look_around agree on per-instance fields for the agent's own chest`() {
        val chest = Building(
            instanceId = UUID.randomUUID(),
            nodeId = nodeId,
            type = BuildingType.STORAGE_CHEST,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = callerId,
            builtAtTick = 1L,
            lastProgressTick = 7L,
            progressSteps = 8,
            totalSteps = 8,
            hpCurrent = 40,
            hpMax = 40,
        )
        val world = SharedWorld(
            location = nodeId,
            nodes = mapOf(nodeId to node),
            regions = mapOf(regionId to region),
            within = mapOf((nodeId to 1) to setOf(nodeId)),
        )
        val registry = registryOf(caller)
        val buildingsLookup = SharedBuildings(listOf(chest))
        val inspect = inspectTool(world, registry, buildings = buildingsLookup)
        val lookAround = LookAroundTool(world, registry, vision(1), activity, NoMapMemory, buildingsLookup, NoOpPlots, NoOpCrops)

        val inspectView = assertNotNull(inspect.dispatch("building", chest.instanceId.toString())).building!!
        val lookView = lookAround.invoke(toolContext)
            .currentNode.buildings.single { it.instanceId == chest.instanceId.toString() }

        assertEquals(lookView.type, inspectView.type)
        assertEquals(lookView.status, inspectView.status)
        assertEquals(lookView.progressSteps, inspectView.progressSteps)
        assertEquals(lookView.totalSteps, inspectView.totalSteps)
        assertEquals(lookView.hpBand, inspectView.hpBand)
        assertEquals(lookView.builderAgentId, inspectView.builderAgentId)
    }

    private fun InspectTool.dispatch(targetType: String, targetId: String) =
        invoke(InspectTargetType.valueOf(targetType.uppercase()), targetId, toolContext)

    private fun inspectTool(
        world: WorldQueryGateway,
        registry: AgentRegistry,
        buildings: BuildingsLookup = NoBuildings,
    ): InspectTool = InspectTool(
        world = world,
        agents = registry,
        vision = vision(1),
        items = NoItems,
        activity = activity,
        tick = FixedTickClock(0L),
        buildings = buildings,
        buildingDefs = NoBuildingDefs,
        chestContents = NoChestContents,
        equipmentInstances = NoEquipmentInstances,
        equipmentSets = NoEquipmentSets,
    )

    private fun registryOf(vararg present: Agent) = object : AgentRegistry {
        private val byId = present.associateBy { it.id }
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = present.filter { it.owner == owner }
    }

    private fun vision(sight: Int) = object : VisionRadius {
        override fun radiusFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<dev.gvart.genesara.world.Building>,
        ): Int = sight
    }

    private class SharedWorld(
        private val location: NodeId,
        private val nodes: Map<NodeId, Node>,
        private val regions: Map<RegionId, Region>,
        private val within: Map<Pair<NodeId, Int>, Set<NodeId>>,
        private val occupants: Map<NodeId, List<AgentId>> = emptyMap(),
        private val bodies: Map<AgentId, BodyView> = emptyMap(),
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = within[origin to radius] ?: emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = bodies[agent]
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> =
            nodeIds.associateWith { occupants[it].orEmpty() }.filterValues { it.isNotEmpty() }
    }

    private class SharedBuildings(private val rows: List<Building>) : BuildingsLookup {
        override fun byId(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun byNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private object NoItems : ItemLookup {
        override fun byId(id: ItemId): Item? = null
        override fun all(): List<Item> = emptyList()
    }

    private object NoBuildings : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private object NoBuildingDefs : BuildingDefLookup {
        override fun byType(type: BuildingType): BuildingDefView? = null
        override fun all(): List<BuildingDefView> = emptyList()
    }

    private object NoChestContents : ChestContentsStore {
        override fun quantityOf(buildingId: UUID, item: ItemId): Int = 0
        override fun contentsOf(buildingId: UUID): Map<ItemId, Int> = emptyMap()
        override fun add(buildingId: UUID, item: ItemId, quantity: Int) = error("not used")
        override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean = error("not used")
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

    private object NoMapMemory : AgentMapMemoryGateway {
        override fun recordVisible(agentId: AgentId, updates: Collection<NodeMemoryUpdate>, tick: Long) = Unit
        override fun recall(agentId: AgentId): List<RecalledNode> = emptyList()
    }

    private object NoOpPlots : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = error("not used")
        override fun findById(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(plotId: UUID, crop: dev.gvart.genesara.world.PlantedCrop): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    private object NoOpCrops : dev.gvart.genesara.world.CropLookup {
        override fun byId(id: dev.gvart.genesara.world.CropId): dev.gvart.genesara.world.Crop? = null
        override fun all(): List<dev.gvart.genesara.world.Crop> = emptyList()
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
