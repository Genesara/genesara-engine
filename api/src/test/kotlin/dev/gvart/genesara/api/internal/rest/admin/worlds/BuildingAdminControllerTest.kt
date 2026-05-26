package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AdminSentinel
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBarView
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingAdminControllerTest {

    private val worldId = WorldId(1L)
    private val regionId = RegionId(7L)
    private val nodeId = NodeId(42L)
    private val foreignNodeId = NodeId(43L)
    private val unknownNodeId = NodeId(99L)

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

    private lateinit var store: InMemoryBuildingsStore
    private lateinit var world: StubWorld
    private lateinit var catalog: StubCatalog
    private lateinit var audit: RecordingAuditLog
    private lateinit var publisher: RecordingPublisher
    private lateinit var controller: BuildingAdminController

    @BeforeEach
    fun setup() {
        store = InMemoryBuildingsStore()
        world = StubWorld(
            nodes = mapOf(nodeId to node, foreignNodeId to foreignNode),
            regions = mapOf(regionId to region, foreignRegion.id to foreignRegion),
        )
        catalog = StubCatalog(
            mapOf(
                BuildingType.WOODEN_WALL to defView(BuildingType.WOODEN_WALL, hp = 100, steps = 3),
                BuildingType.CAMPFIRE to defView(BuildingType.CAMPFIRE, hp = 30, steps = 1),
            )
        )
        audit = RecordingAuditLog()
        publisher = RecordingPublisher()
        controller = BuildingAdminController(
            store = store,
            world = world,
            catalog = catalog,
            tick = FixedTickClock(500L),
            auditLog = audit,
            publisher = publisher,
        )
    }

    @Test
    fun `create defaults status and HP and progress from the catalog`() {
        val response = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        )

        val dto = assertNotNull(response.body)
        assertEquals(BuildingStatus.ACTIVE, dto.status)
        assertEquals(100, dto.hpCurrent)
        assertEquals(100, dto.hpMax)
        assertEquals(3, dto.progressSteps)
        assertEquals(3, dto.totalSteps)
        assertEquals(AdminSentinel.agentId.id, dto.builtByAgentId)
        assertEquals(500L, dto.builtAtTick)

        assertEquals(1, store.rows.size)
        assertEquals(BuildingType.WOODEN_WALL, store.rows.single().type)
    }

    @Test
    fun `create accepts a half-HP ACTIVE WOODEN_WALL and lists it via GET`() {
        controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(
                type = BuildingType.WOODEN_WALL,
                status = BuildingStatus.ACTIVE,
                hpCurrent = 50,
                hpMax = 100,
            ),
            admin = admin,
        )

        val listed = controller.list(worldId.value, nodeId.value)

        assertEquals(1, listed.size)
        assertEquals(BuildingStatus.ACTIVE, listed.single().status)
        assertEquals(50, listed.single().hpCurrent)
        assertEquals(100, listed.single().hpMax)
    }

    @Test
    fun `create accepts an UNDER_CONSTRUCTION building at any progress step`() {
        val response = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(
                type = BuildingType.WOODEN_WALL,
                status = BuildingStatus.UNDER_CONSTRUCTION,
                progressSteps = 1,
                totalSteps = 3,
            ),
            admin = admin,
        )

        val dto = assertNotNull(response.body)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, dto.status)
        assertEquals(1, dto.progressSteps)
        assertEquals(3, dto.totalSteps)
    }

    @Test
    fun `create rejects ACTIVE with partial progress`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = nodeId.value,
                req = CreateBuildingRequest(
                    type = BuildingType.WOODEN_WALL,
                    status = BuildingStatus.ACTIVE,
                    progressSteps = 1,
                    totalSteps = 3,
                ),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
        assertTrue(error.reason!!.contains("status (ACTIVE) must match completion"))
    }

    @Test
    fun `create rejects UNDER_CONSTRUCTION at full progress`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = nodeId.value,
                req = CreateBuildingRequest(
                    type = BuildingType.WOODEN_WALL,
                    status = BuildingStatus.UNDER_CONSTRUCTION,
                    progressSteps = 3,
                    totalSteps = 3,
                ),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `create rejects hpCurrent above hpMax`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = nodeId.value,
                req = CreateBuildingRequest(
                    type = BuildingType.WOODEN_WALL,
                    hpCurrent = 200,
                    hpMax = 100,
                ),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
        assertTrue(error.reason!!.contains("hpCurrent (200)"))
    }

    @Test
    fun `create rejects non-positive hpMax`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = nodeId.value,
                req = CreateBuildingRequest(
                    type = BuildingType.WOODEN_WALL,
                    hpMax = 0,
                ),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `create rejects progressSteps above totalSteps`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = nodeId.value,
                req = CreateBuildingRequest(
                    type = BuildingType.WOODEN_WALL,
                    progressSteps = 5,
                    totalSteps = 3,
                ),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `create rejects nodes that don't belong to the world`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = foreignNodeId.value,
                req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
                admin = admin,
            )
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `create rejects unknown nodes`() {
        val error = assertThrows<ResponseStatusException> {
            controller.create(
                worldId = worldId.value,
                nodeId = unknownNodeId.value,
                req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
                admin = admin,
            )
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `create emits BuildingAdminEdited and writes one audit row`() {
        val response = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(
                type = BuildingType.WOODEN_WALL,
                status = BuildingStatus.ACTIVE,
                hpCurrent = 50,
            ),
            admin = admin,
        )

        val emitted = publisher.events.single() as EnvironmentEvent.BuildingAdminEdited
        assertEquals(response.body!!.instanceId, emitted.instanceId)
        assertEquals(false, emitted.removed)
        assertEquals(admin.id.id, emitted.byAdminId)
        assertTrue("status" in emitted.changedFields)
        assertTrue("hpCurrent" in emitted.changedFields)
        assertTrue("hpMax" !in emitted.changedFields)

        val auditRow = audit.recorded.single()
        assertEquals(admin.id, auditRow.adminId)
        assertEquals("building.create", auditRow.action)
        assertEquals("building", auditRow.target)
        assertEquals(response.body!!.instanceId.toString(), auditRow.targetId)
    }

    @Test
    fun `patch overwrites only Set fields and keeps Skip fields untouched`() {
        val created = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        ).body!!

        val patched = controller.patch(
            worldId = worldId.value,
            instanceId = created.instanceId,
            req = PatchBuildingRequest(hpCurrent = MaybeSet.Set(25)),
            admin = admin,
        )

        assertEquals(25, patched.hpCurrent)
        assertEquals(100, patched.hpMax)
        assertEquals(BuildingStatus.ACTIVE, patched.status)
        assertEquals(3, patched.progressSteps)
        assertEquals(3, patched.totalSteps)
    }

    @Test
    fun `patch can flip an ACTIVE building to UNDER_CONSTRUCTION when progress goes below total`() {
        val created = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        ).body!!

        val patched = controller.patch(
            worldId = worldId.value,
            instanceId = created.instanceId,
            req = PatchBuildingRequest(
                status = MaybeSet.Set(BuildingStatus.UNDER_CONSTRUCTION),
                progressSteps = MaybeSet.Set(1),
            ),
            admin = admin,
        )

        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, patched.status)
        assertEquals(1, patched.progressSteps)
    }

    @Test
    fun `patch rejects status without matching progress`() {
        val created = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        ).body!!

        val error = assertThrows<ResponseStatusException> {
            controller.patch(
                worldId = worldId.value,
                instanceId = created.instanceId,
                req = PatchBuildingRequest(status = MaybeSet.Set(BuildingStatus.UNDER_CONSTRUCTION)),
                admin = admin,
            )
        }
        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `patch returns 404 for an unknown instance id`() {
        val error = assertThrows<ResponseStatusException> {
            controller.patch(
                worldId = worldId.value,
                instanceId = UUID.randomUUID(),
                req = PatchBuildingRequest(hpCurrent = MaybeSet.Set(25)),
                admin = admin,
            )
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `patch rejects when the building belongs to a different world than the path id`() {
        store.rows += Building(
            instanceId = UUID.randomUUID(),
            nodeId = foreignNodeId,
            type = BuildingType.WOODEN_WALL,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = AdminSentinel.agentId,
            builtAtTick = 0L,
            lastProgressTick = 0L,
            progressSteps = 3,
            totalSteps = 3,
            hpCurrent = 100,
            hpMax = 100,
        )
        val foreign = store.rows.last()

        val error = assertThrows<ResponseStatusException> {
            controller.patch(
                worldId = worldId.value,
                instanceId = foreign.instanceId,
                req = PatchBuildingRequest(hpCurrent = MaybeSet.Set(50)),
                admin = admin,
            )
        }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `patch emits BuildingAdminEdited with the changed fields and writes one audit row`() {
        val created = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        ).body!!
        publisher.events.clear()
        audit.recorded.clear()

        controller.patch(
            worldId = worldId.value,
            instanceId = created.instanceId,
            req = PatchBuildingRequest(hpCurrent = MaybeSet.Set(25)),
            admin = admin,
        )

        val emitted = publisher.events.single() as EnvironmentEvent.BuildingAdminEdited
        assertEquals(false, emitted.removed)
        assertEquals(setOf("hpCurrent"), emitted.changedFields)

        val auditRow = audit.recorded.single()
        assertEquals("building.patch", auditRow.action)
    }

    @Test
    fun `delete removes the row and emits BuildingAdminEdited with removed=true`() {
        val created = controller.create(
            worldId = worldId.value,
            nodeId = nodeId.value,
            req = CreateBuildingRequest(type = BuildingType.WOODEN_WALL),
            admin = admin,
        ).body!!
        publisher.events.clear()
        audit.recorded.clear()

        val response = controller.delete(
            worldId = worldId.value,
            instanceId = created.instanceId,
            admin = admin,
        )

        assertEquals(204, response.statusCode.value())
        assertNull(store.findById(created.instanceId))

        val emitted = publisher.events.single() as EnvironmentEvent.BuildingAdminEdited
        assertEquals(true, emitted.removed)
        assertEquals(created.instanceId, emitted.instanceId)

        val auditRow = audit.recorded.single()
        assertEquals("building.delete", auditRow.action)
    }

    @Test
    fun `delete returns 404 for an unknown instance id`() {
        val error = assertThrows<ResponseStatusException> {
            controller.delete(worldId.value, UUID.randomUUID(), admin)
        }
        assertEquals(404, error.statusCode.value())
    }

    private fun defView(type: BuildingType, hp: Int, steps: Int) = BuildingDefView(
        type = type,
        skillBars = listOf(BuildingBarView(skill = dev.gvart.genesara.player.SkillId("WOODWORKING"), level = 0, steps = steps, materialsPerStep = emptyMap())),
        staminaPerStep = 5,
        hp = hp,
        categoryHint = BuildingCategoryHint.DEFENSIVE,
    )

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
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId) = null
        override fun inventoryOf(agent: AgentId) = dev.gvart.genesara.world.InventoryView(entries = emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId) = emptyList<dev.gvart.genesara.world.GroundItemView>()
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

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = recorded
            .drop(after.toInt())
            .take(limit)
            .mapIndexed { idx, r ->
                AdminAuditEntry(
                    seq = (after + idx + 1L),
                    adminId = r.adminId,
                    action = r.action,
                    target = r.target,
                    targetId = r.targetId,
                    payload = r.payload,
                    tick = r.tick,
                    occurredAt = Instant.EPOCH,
                )
            }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events: MutableList<WorldEvent> = mutableListOf()
        override fun publishEvent(event: Any) {
            (event as? WorldEvent)?.let { events += it }
        }
    }
}
