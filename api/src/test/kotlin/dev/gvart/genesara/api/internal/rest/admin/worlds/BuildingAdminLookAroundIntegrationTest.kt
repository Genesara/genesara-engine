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
import dev.gvart.genesara.world.BuildingBarView
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildingAdminLookAroundIntegrationTest {

    private val worldId = WorldId(1L)
    private val regionId = RegionId(7L)
    private val currentNodeId = NodeId(42L)
    private val adjacentNodeId = NodeId(43L)

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
    private val currentNode = Node(
        id = currentNodeId,
        regionId = regionId,
        q = 0, r = 0,
        terrain = Terrain.PLAINS,
        adjacency = setOf(adjacentNodeId),
    )
    private val adjacentNode = Node(
        id = adjacentNodeId,
        regionId = regionId,
        q = 1, r = 0,
        terrain = Terrain.PLAINS,
        adjacency = setOf(currentNodeId),
    )

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")

    @Test
    fun `admin-placed half-HP ACTIVE WOODEN_WALL surfaces via BuildingsLookup on same node`() {
        val store = InMemoryBuildingsStore()
        val world = StubWorld(
            nodes = mapOf(currentNodeId to currentNode, adjacentNodeId to adjacentNode),
            regions = mapOf(regionId to region),
        )
        val catalog = StubCatalog(
            mapOf(BuildingType.WOODEN_WALL to defView(BuildingType.WOODEN_WALL, hp = 100, steps = 3))
        )
        val controller = BuildingAdminController(
            store = store,
            world = world,
            catalog = catalog,
            tick = FixedTickClock(500L),
            auditLog = RecordingAuditLog(),
            publisher = RecordingPublisher(),
        )
        val lookup = StoreBackedBuildingsLookup(store)

        val placed = controller.create(
            worldId = worldId.value,
            nodeId = currentNodeId.value,
            req = CreateBuildingRequest(
                type = BuildingType.WOODEN_WALL,
                status = BuildingStatus.ACTIVE,
                hpCurrent = 50,
                hpMax = 100,
            ),
            admin = admin,
        ).body!!

        val onSame = lookup.byNode(currentNodeId)
        assertEquals(1, onSame.size)
        val seen = onSame.single()
        assertEquals(placed.instanceId, seen.instanceId)
        assertEquals(BuildingType.WOODEN_WALL, seen.type)
        assertEquals(BuildingStatus.ACTIVE, seen.status)
        assertEquals(50, seen.hpCurrent)
        assertEquals(100, seen.hpMax)
        assertEquals(AdminSentinel.agentId, seen.builtByAgentId)

        val batched = lookup.byNodes(setOf(currentNodeId, adjacentNodeId))
        assertNotNull(batched[currentNodeId])
        assertTrue(batched[adjacentNodeId].isNullOrEmpty())
        assertEquals(placed.instanceId, batched[currentNodeId]!!.single().instanceId)
    }

    private fun defView(type: BuildingType, hp: Int, steps: Int) = BuildingDefView(
        type = type,
        skillBars = listOf(
            BuildingBarView(
                skill = dev.gvart.genesara.player.SkillId("WOODWORKING"),
                level = 0,
                steps = steps,
                materialsPerStep = emptyMap(),
            )
        ),
        staminaPerStep = 5,
        hp = hp,
        categoryHint = BuildingCategoryHint.DEFENSIVE,
    )

    private class StoreBackedBuildingsLookup(private val store: BuildingsStore) : BuildingsLookup {
        override fun byId(id: UUID): Building? = store.findById(id)
        override fun byNode(node: NodeId): List<Building> = store.listAtNode(node)
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = store.listByNodes(nodes)
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> =
            store.listAtNode(node).filter { it.status == BuildingStatus.ACTIVE }
    }

    private class InMemoryBuildingsStore : BuildingsStore {
        val rows: MutableList<Building> = mutableListOf()
        override fun insert(building: Building) { rows += building }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
            rows.firstOrNull {
                it.nodeId == node && it.builtByAgentId == agent && it.type == type &&
                    it.status == BuildingStatus.UNDER_CONSTRUCTION
            }
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? =
            rows.firstOrNull { it.nodeId == node && it.type == type }
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = null
        override fun complete(id: UUID, asOfTick: Long): Building? = null
        override fun update(updated: Building): Building? {
            val idx = rows.indexOfFirst { it.instanceId == updated.instanceId }
            if (idx < 0) return null
            rows[idx] = updated
            return updated
        }
        override fun delete(id: UUID): Boolean = rows.removeAll { it.instanceId == id }
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

    private class RecordingAuditLog : AdminAuditLog {
        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long = 0L

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = emptyList()
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) {
            event as? WorldEvent
        }
    }
}
