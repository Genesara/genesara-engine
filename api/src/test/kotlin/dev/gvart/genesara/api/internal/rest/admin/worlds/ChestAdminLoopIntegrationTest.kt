package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBarView
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.events.WorldEvent
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChestAdminLoopIntegrationTest {

    private val worldId = WorldId(1L)
    private val regionId = RegionId(7L)
    private val nodeId = NodeId(42L)
    private val region = Region(
        id = regionId, worldId = worldId, sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 0.0), faceVertices = emptyList(), neighbors = emptySet(),
    )
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")

    @Test
    fun `place chest, seed via PUT, agent reads same contents through ChestContentsStore`() {
        val buildings = InMemoryBuildingsStore()
        val chest = InMemoryChestContentsStore()
        val world = StubWorld(mapOf(nodeId to node), mapOf(regionId to region))
        val tick = FixedTickClock(500L)
        val audit = NullAuditLog()
        val publisher = NullPublisher()
        val catalog = StubCatalog(
            mapOf(BuildingType.STORAGE_CHEST to defView(BuildingType.STORAGE_CHEST, hp = 40, steps = 8)),
        )

        val buildingAdmin = BuildingAdminController(
            store = buildings, world = world, catalog = catalog,
            tick = tick, auditLog = audit, publisher = publisher,
        )
        val chestAdmin = ChestAdminController(
            buildings = buildings, chestContents = chest,
            world = world, auditLog = audit, tick = tick,
        )

        val placed = buildingAdmin.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.STORAGE_CHEST),
            admin = admin,
        ).body!!

        chestAdmin.replace(
            worldId = worldId.value,
            instanceId = placed.instanceId,
            req = ReplaceChestContentsRequest(contents = mapOf("WOOD" to 5, "STONE" to 3)),
            admin = admin,
        )

        val visible = chest.contentsOf(placed.instanceId)
        assertEquals(5, visible[ItemId("WOOD")])
        assertEquals(3, visible[ItemId("STONE")])

        val withdrew = chest.remove(placed.instanceId, ItemId("WOOD"), 2)
        assertTrue(withdrew)
        assertEquals(3, chest.quantityOf(placed.instanceId, ItemId("WOOD")))
        assertEquals(3, chest.quantityOf(placed.instanceId, ItemId("STONE")))
    }

    @Test
    fun `PATCH increments contents already visible to the withdraw path`() {
        val buildings = InMemoryBuildingsStore()
        val chest = InMemoryChestContentsStore()
        val world = StubWorld(mapOf(nodeId to node), mapOf(regionId to region))
        val tick = FixedTickClock(500L)
        val catalog = StubCatalog(
            mapOf(BuildingType.STORAGE_CHEST to defView(BuildingType.STORAGE_CHEST, hp = 40, steps = 8)),
        )
        val buildingAdmin = BuildingAdminController(
            store = buildings, world = world, catalog = catalog,
            tick = tick, auditLog = NullAuditLog(), publisher = NullPublisher(),
        )
        val chestAdmin = ChestAdminController(
            buildings = buildings, chestContents = chest,
            world = world, auditLog = NullAuditLog(), tick = tick,
        )

        val placed = buildingAdmin.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.STORAGE_CHEST),
            admin = admin,
        ).body!!

        chestAdmin.patch(
            worldId.value, placed.instanceId,
            PatchChestContentsRequest(itemId = "BERRY", delta = 4), admin,
        )
        chestAdmin.patch(
            worldId.value, placed.instanceId,
            PatchChestContentsRequest(itemId = "BERRY", delta = 2), admin,
        )

        assertEquals(6, chest.quantityOf(placed.instanceId, ItemId("BERRY")))
    }

    private fun defView(type: BuildingType, hp: Int, steps: Int) = BuildingDefView(
        type = type,
        skillBars = listOf(
            BuildingBarView(
                skill = dev.gvart.genesara.player.SkillId("CARPENTRY"),
                level = 0, steps = steps, materialsPerStep = emptyMap(),
            ),
        ),
        staminaPerStep = 5,
        hp = hp,
        categoryHint = BuildingCategoryHint.STORAGE,
    )

    private class InMemoryBuildingsStore : BuildingsStore {
        val rows: MutableList<Building> = mutableListOf()
        override fun insert(building: Building) { rows += building }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? = null
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? = null
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = null
        override fun complete(id: UUID, asOfTick: Long): Building? = null
        override fun update(updated: Building): Building? = null
        override fun delete(id: UUID): Boolean = rows.removeAll { it.instanceId == id }
    }

    private class InMemoryChestContentsStore : ChestContentsStore {
        private val rows: MutableMap<Pair<UUID, ItemId>, Int> = mutableMapOf()
        override fun quantityOf(buildingId: UUID, item: ItemId): Int = rows[buildingId to item] ?: 0
        override fun contentsOf(buildingId: UUID): Map<ItemId, Int> =
            rows.filterKeys { it.first == buildingId }.mapKeys { it.key.second }
        override fun add(buildingId: UUID, item: ItemId, quantity: Int) {
            require(quantity > 0)
            rows.merge(buildingId to item, quantity, Int::plus)
        }
        override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean {
            require(quantity > 0)
            val key = buildingId to item
            val have = rows[key] ?: 0
            if (have < quantity) return false
            val next = have - quantity
            if (next == 0) rows.remove(key) else rows[key] = next
            return true
        }
        override fun replace(buildingId: UUID, contents: Map<ItemId, Int>) {
            contents.values.forEach { require(it > 0) }
            rows.keys.removeAll { it.first == buildingId }
            contents.forEach { (item, qty) -> rows[buildingId to item] = qty }
        }
        override fun removeAll(buildingId: UUID, item: ItemId): Boolean =
            rows.remove(buildingId to item) != null
    }

    private class StubWorld(
        private val nodes: Map<NodeId, Node>,
        private val regions: Map<RegionId, Region>,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = regions[id]
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId) = null
        override fun inventoryOf(agent: AgentId) = InventoryView(entries = emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long) = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId) = emptyList<GroundItemView>()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>) = emptyMap<NodeId, List<AgentId>>()
    }

    private class StubCatalog(private val byType: Map<BuildingType, BuildingDefView>) : BuildingDefLookup {
        override fun byType(type: BuildingType): BuildingDefView? = byType[type]
        override fun all(): List<BuildingDefView> = byType.values.toList()
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }

    private class NullAuditLog : AdminAuditLog {
        override fun record(
            adminId: AdminId, action: String, target: String, targetId: String?,
            payload: Map<String, Any?>, tick: Long,
        ): Long = 0L
        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = emptyList()
    }

    private class NullPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) {
            event as? WorldEvent
        }
    }
}
