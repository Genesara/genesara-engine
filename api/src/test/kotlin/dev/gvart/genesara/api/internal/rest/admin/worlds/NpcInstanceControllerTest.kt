package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.environment.AdminNpcGateway
import dev.gvart.genesara.world.environment.AdminNpcGatewayError
import dev.gvart.genesara.world.environment.KillOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcInstanceControllerTest {

    private val worldId = WorldId(7L)
    private val otherWorldId = WorldId(8L)
    private val regionId = RegionId(100L)
    private val foreignRegionId = RegionId(200L)
    private val nodeAId = NodeId(11L)
    private val nodeBId = NodeId(12L)
    private val foreignNodeId = NodeId(99L)
    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")
    private val tick = 42L

    private lateinit var gateway: RecordingAdminNpcGateway
    private lateinit var npcsStore: StubNpcsStore
    private lateinit var query: StubWorldQuery
    private lateinit var audit: RecordingAuditLog
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        gateway = RecordingAdminNpcGateway()
        npcsStore = StubNpcsStore()
        query = StubWorldQuery(
            nodes = mapOf(
                nodeAId to Node(nodeAId, regionId, 0, 0, Terrain.PLAINS, emptySet()),
                nodeBId to Node(nodeBId, regionId, 1, 0, Terrain.PLAINS, emptySet()),
                foreignNodeId to Node(foreignNodeId, foreignRegionId, 0, 0, Terrain.PLAINS, emptySet()),
            ),
            regions = mapOf(
                regionId to region(regionId, worldId),
                foreignRegionId to region(foreignRegionId, otherWorldId),
            ),
        )
        audit = RecordingAuditLog()
        mvc = MockMvcBuilders.standaloneSetup(
            NpcInstanceController(gateway, npcsStore, query, audit, FixedTickClock(tick)),
        )
            .setControllerAdvice(GlobalExceptionAdvice())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(admin, null, emptyList())
    }

    @org.junit.jupiter.api.AfterEach
    fun clearSecurity() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `spawn creates an npc and writes audit row`() {
        val spawned = Npc(
            id = NpcId(UUID.randomUUID()),
            type = NpcType("GRAY_WOLF"),
            nodeId = nodeAId,
            spawnNodeId = nodeAId,
            hpCurrent = 25,
            hpMax = 30,
            spawnedAtTick = tick,
            lastAttackTick = tick,
        )
        gateway.spawnResult = spawned

        mvc.post("/admin/worlds/${worldId.value}/nodes/${nodeAId.value}/npcs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"GRAY_WOLF","hp":25}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.type") { value("GRAY_WOLF") }
            jsonPath("$.nodeId") { value(nodeAId.value) }
            jsonPath("$.hpCurrent") { value(25) }
        }

        assertEquals(NpcType("GRAY_WOLF"), gateway.lastSpawnType)
        assertEquals(25, gateway.lastSpawnHp)
        val entry = audit.entries.single()
        assertEquals("npc.spawn", entry.action)
        assertEquals(spawned.id.value.toString(), entry.targetId)
        assertEquals("GRAY_WOLF", entry.payload["type"])
        assertEquals(worldId.value, entry.payload["worldId"])
    }

    @Test
    fun `spawn defaults hp to catalog hpMax via the gateway`() {
        val spawned = sampleNpc()
        gateway.spawnResult = spawned

        mvc.post("/admin/worlds/${worldId.value}/nodes/${nodeAId.value}/npcs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"GRAY_WOLF"}"""
        }.andExpect { status { isCreated() } }

        assertNull(gateway.lastSpawnHp)
    }

    @Test
    fun `spawn rejects unknown npc type with 400`() {
        gateway.spawnError = AdminNpcGatewayError.UnknownType(NpcType("PHANTOM"))

        mvc.post("/admin/worlds/${worldId.value}/nodes/${nodeAId.value}/npcs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"PHANTOM"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("unknown NPC type PHANTOM") }
        }

        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `spawn rejects node that does not belong to world with 404`() {
        mvc.post("/admin/worlds/${worldId.value}/nodes/${foreignNodeId.value}/npcs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"GRAY_WOLF"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("node ${foreignNodeId.value} does not belong to world ${worldId.value}") }
        }

        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `spawn returns 404 when the node is unknown`() {
        mvc.post("/admin/worlds/${worldId.value}/nodes/9999/npcs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"GRAY_WOLF"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("node 9999 not found") }
        }
    }

    @Test
    fun `list returns npcs at node`() {
        val npc = sampleNpc()
        gateway.listResult = listOf(npc)

        mvc.get("/admin/worlds/${worldId.value}/nodes/${nodeAId.value}/npcs") {
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(npc.id.value.toString()) }
        }
    }

    @Test
    fun `patch updates hp and writes audit row`() {
        val existing = sampleNpc()
        npcsStore.byId[existing.id] = existing
        gateway.updateResult = existing.copy(hpCurrent = 5)

        mvc.patch("/admin/worlds/${worldId.value}/npcs/${existing.id.value}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"hp":5}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.hpCurrent") { value(5) }
        }

        assertEquals(5, gateway.lastUpdateHp)
        assertNull(gateway.lastUpdateNode)
        val entry = audit.entries.single()
        assertEquals("npc.update", entry.action)
        assertEquals(5, entry.payload["hp"])
    }

    @Test
    fun `patch moves npc to another node in same world`() {
        val existing = sampleNpc()
        npcsStore.byId[existing.id] = existing
        gateway.updateResult = existing.copy(nodeId = nodeBId)

        mvc.patch("/admin/worlds/${worldId.value}/npcs/${existing.id.value}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodeId":${nodeBId.value}}"""
        }.andExpect { status { isOk() } }

        assertEquals(nodeBId, gateway.lastUpdateNode)
        assertEquals(nodeBId.value, audit.entries.single().payload["nodeId"])
    }

    @Test
    fun `patch rejects target node outside world`() {
        val existing = sampleNpc()
        npcsStore.byId[existing.id] = existing

        mvc.patch("/admin/worlds/${worldId.value}/npcs/${existing.id.value}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodeId":${foreignNodeId.value}}"""
        }.andExpect {
            status { isNotFound() }
        }

        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `patch returns 404 when npc not found`() {
        val id = UUID.randomUUID()
        mvc.patch("/admin/worlds/${worldId.value}/npcs/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"hp":5}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("npc $id not found") }
        }
    }

    @Test
    fun `delete with default silent false kills via gateway and writes audit row`() {
        val existing = sampleNpc()
        npcsStore.byId[existing.id] = existing
        gateway.killResult = KillOutcome(npc = existing, drops = emptyList())

        mvc.delete("/admin/worlds/${worldId.value}/npcs/${existing.id.value}") {
        }.andExpect { status { isNoContent() } }

        assertEquals(false, gateway.lastKillSilent)
        val entry = audit.entries.single()
        assertEquals("npc.kill", entry.action)
        assertEquals(false, entry.payload["silent"])
    }

    @Test
    fun `delete with silent true skips events but still writes audit row`() {
        val existing = sampleNpc()
        npcsStore.byId[existing.id] = existing
        gateway.killResult = KillOutcome(npc = existing, drops = emptyList())

        mvc.delete("/admin/worlds/${worldId.value}/npcs/${existing.id.value}?silent=true") {
        }.andExpect { status { isNoContent() } }

        assertEquals(true, gateway.lastKillSilent)
        assertEquals(true, audit.entries.single().payload["silent"])
    }

    @Test
    fun `delete returns 404 when npc not found`() {
        val id = UUID.randomUUID()
        mvc.delete("/admin/worlds/${worldId.value}/npcs/$id") {
        }.andExpect { status { isNotFound() } }
    }

    private fun sampleNpc() = Npc(
        id = NpcId(UUID.randomUUID()),
        type = NpcType("GRAY_WOLF"),
        nodeId = nodeAId,
        spawnNodeId = nodeAId,
        hpCurrent = 30,
        hpMax = 30,
        spawnedAtTick = tick,
        lastAttackTick = tick,
    )

    private fun region(id: RegionId, world: WorldId) = Region(
        id = id,
        worldId = world,
        sphereIndex = 0,
        biome = Biome.FOREST,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private class RecordingAdminNpcGateway : AdminNpcGateway {
        var spawnResult: Npc? = null
        var spawnError: RuntimeException? = null
        var lastSpawnType: NpcType? = null
        var lastSpawnHp: Int? = null
        var listResult: List<Npc> = emptyList()
        var updateResult: Npc? = null
        var lastUpdateHp: Int? = null
        var lastUpdateNode: NodeId? = null
        var killResult: KillOutcome? = null
        var lastKillSilent: Boolean? = null

        override fun spawn(nodeId: NodeId, type: NpcType, hp: Int?, tick: Long): Npc {
            lastSpawnType = type
            lastSpawnHp = hp
            spawnError?.let { throw it }
            return spawnResult ?: error("spawnResult not set")
        }

        override fun listAtNode(nodeId: NodeId): List<Npc> = listResult

        override fun update(npcId: NpcId, hp: Int?, nodeId: NodeId?, tick: Long): Npc {
            lastUpdateHp = hp
            lastUpdateNode = nodeId
            return updateResult ?: error("updateResult not set")
        }

        override fun kill(npcId: NpcId, silent: Boolean, tick: Long): KillOutcome {
            lastKillSilent = silent
            return killResult ?: error("killResult not set")
        }
    }

    private class StubNpcsStore : NpcsStore {
        val byId = mutableMapOf<NpcId, Npc>()
        override fun insert(npc: Npc) = error("unused")
        override fun findById(npcId: NpcId): Npc? = byId[npcId]
        override fun byNodes(nodeIds: Collection<NodeId>): List<Npc> = error("unused")
        override fun delete(npcId: NpcId): Boolean = error("unused")
        override fun countAtNode(nodeId: NodeId): Int = error("unused")
        override fun update(npc: Npc) = error("unused")
    }

    private class StubWorldQuery(
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
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class RecordingAuditLog : AdminAuditLog {
        val entries = mutableListOf<AdminAuditEntry>()
        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            entries += AdminAuditEntry(
                seq = entries.size + 1L,
                adminId = adminId,
                action = action,
                target = target,
                targetId = targetId,
                payload = payload,
                tick = tick,
                occurredAt = java.time.Instant.EPOCH,
            )
            return entries.last().seq
        }
        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = error("unused")
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
