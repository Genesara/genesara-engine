package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AdminSentinel
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChestAdminControllerTest {

    private val worldId = WorldId(1L)
    private val regionId = RegionId(7L)
    private val nodeId = NodeId(42L)
    private val foreignNodeId = NodeId(43L)

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
    private val foreignRegion = Region(
        id = RegionId(8L),
        worldId = WorldId(2L),
        sphereIndex = 0,
        biome = Biome.FOREST,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 0.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val foreignNode = Node(foreignNodeId, foreignRegion.id, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")

    private lateinit var buildings: InMemoryBuildingsStore
    private lateinit var chest: InMemoryChestContentsStore
    private lateinit var world: StubWorld
    private lateinit var audit: RecordingAuditLog
    private lateinit var controller: ChestAdminController

    private lateinit var chestId: UUID

    @BeforeEach
    fun setup() {
        buildings = InMemoryBuildingsStore()
        chest = InMemoryChestContentsStore()
        world = StubWorld(
            nodes = mapOf(nodeId to node, foreignNodeId to foreignNode),
            regions = mapOf(regionId to region, foreignRegion.id to foreignRegion),
        )
        audit = RecordingAuditLog()
        controller = ChestAdminController(
            buildings = buildings,
            chestContents = chest,
            world = world,
            auditLog = audit,
            tick = FixedTickClock(500L),
        )
        chestId = placeChest(BuildingType.STORAGE_CHEST, nodeId)
    }

    @Test
    fun `GET returns empty contents for a freshly placed chest`() {
        val dto = controller.get(worldId.value, chestId)

        assertEquals(chestId, dto.instanceId)
        assertEquals(nodeId.value, dto.nodeId)
        assertTrue(dto.contents.isEmpty())
    }

    @Test
    fun `PUT replaces contents atomically — entries not in body are removed`() {
        chest.add(chestId, ItemId("WOOD"), 3)
        chest.add(chestId, ItemId("STONE"), 5)

        val dto = controller.replace(
            worldId.value,
            chestId,
            ReplaceChestContentsRequest(contents = mapOf("WOOD" to 7, "BERRY" to 2)),
            admin,
        )

        assertEquals(mapOf("WOOD" to 7, "BERRY" to 2), dto.contents)
        assertEquals(0, chest.quantityOf(chestId, ItemId("STONE")))
        assertEquals(7, chest.quantityOf(chestId, ItemId("WOOD")))
    }

    @Test
    fun `PUT rejects non-positive quantities`() {
        val error = assertThrows<ResponseStatusException> {
            controller.replace(
                worldId.value,
                chestId,
                ReplaceChestContentsRequest(contents = mapOf("WOOD" to 0)),
                admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `PUT writes one audit row`() {
        controller.replace(
            worldId.value,
            chestId,
            ReplaceChestContentsRequest(contents = mapOf("WOOD" to 1)),
            admin,
        )

        val row = audit.recorded.single()
        assertEquals("chest.replace", row.action)
        assertEquals("chest", row.target)
        assertEquals(chestId.toString(), row.targetId)
    }

    @Test
    fun `PATCH positive delta adds quantity`() {
        chest.add(chestId, ItemId("WOOD"), 2)

        val dto = controller.patch(
            worldId.value,
            chestId,
            PatchChestContentsRequest(itemId = "WOOD", delta = 3),
            admin,
        )

        assertEquals(5, dto.contents["WOOD"])
    }

    @Test
    fun `PATCH negative delta removes quantity`() {
        chest.add(chestId, ItemId("WOOD"), 5)

        val dto = controller.patch(
            worldId.value,
            chestId,
            PatchChestContentsRequest(itemId = "WOOD", delta = -2),
            admin,
        )

        assertEquals(3, dto.contents["WOOD"])
    }

    @Test
    fun `PATCH over-remove rejects with 400 and does not mutate`() {
        chest.add(chestId, ItemId("WOOD"), 2)

        val error = assertThrows<ResponseStatusException> {
            controller.patch(
                worldId.value,
                chestId,
                PatchChestContentsRequest(itemId = "WOOD", delta = -5),
                admin,
            )
        }

        assertEquals(400, error.statusCode.value())
        assertEquals(2, chest.quantityOf(chestId, ItemId("WOOD")))
        assertTrue(audit.recorded.isEmpty())
    }

    @Test
    fun `PATCH zero delta rejects`() {
        val error = assertThrows<ResponseStatusException> {
            controller.patch(
                worldId.value,
                chestId,
                PatchChestContentsRequest(itemId = "WOOD", delta = 0),
                admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `DELETE removes all of one item but keeps others`() {
        chest.add(chestId, ItemId("WOOD"), 3)
        chest.add(chestId, ItemId("STONE"), 5)

        val response = controller.delete(worldId.value, chestId, "WOOD", admin)

        assertEquals(204, response.statusCode.value())
        assertEquals(0, chest.quantityOf(chestId, ItemId("WOOD")))
        assertEquals(5, chest.quantityOf(chestId, ItemId("STONE")))
    }

    @Test
    fun `non-chest building rejects with 400`() {
        val campfireId = placeChest(BuildingType.CAMPFIRE, nodeId)

        val error = assertThrows<ResponseStatusException> {
            controller.get(worldId.value, campfireId)
        }
        assertEquals(400, error.statusCode.value())
        assertTrue(error.reason!!.contains("CAMPFIRE"))
    }

    @Test
    fun `unknown building returns 404`() {
        val error = assertThrows<ResponseStatusException> {
            controller.get(worldId.value, UUID.randomUUID())
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `building in different world returns 404`() {
        val foreignChestId = placeChest(BuildingType.STORAGE_CHEST, foreignNodeId)

        val error = assertThrows<ResponseStatusException> {
            controller.get(worldId.value, foreignChestId)
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `holdsChest predicate`() {
        assertTrue(BuildingType.STORAGE_CHEST.holdsChest())
        assertFalse(BuildingType.CAMPFIRE.holdsChest())
        assertFalse(BuildingType.WOODEN_WALL.holdsChest())
    }

    private fun placeChest(type: BuildingType, at: NodeId): UUID {
        val id = UUID.randomUUID()
        buildings.rows += Building(
            instanceId = id,
            nodeId = at,
            type = type,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = AdminSentinel.agentId,
            builtAtTick = 0L,
            lastProgressTick = 0L,
            progressSteps = 1,
            totalSteps = 1,
            hpCurrent = 100,
            hpMax = 100,
        )
        return id
    }

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

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }

    private class RecordingAuditLog : AdminAuditLog {
        data class Recorded(
            val adminId: AdminId,
            val action: String,
            val target: String,
            val targetId: String?,
            val payload: Map<String, Any?>,
            val tick: Long,
        )

        val recorded: MutableList<Recorded> = mutableListOf()
        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            recorded += Recorded(adminId, action, target, targetId, payload, tick)
            return recorded.size.toLong()
        }

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = emptyList()
    }
}
