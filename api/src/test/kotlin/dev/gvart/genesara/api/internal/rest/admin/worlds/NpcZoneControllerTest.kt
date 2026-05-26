package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneAdminError
import dev.gvart.genesara.world.NpcZoneAdminGateway
import dev.gvart.genesara.world.NpcZoneCreateSpec
import dev.gvart.genesara.world.NpcZonePatch
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldId
import org.junit.jupiter.api.AfterEach
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcZoneControllerTest {

    private val worldId = WorldId(7L)
    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")
    private val tick = 42L

    private lateinit var gateway: RecordingGateway
    private lateinit var audit: RecordingAuditLog
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        gateway = RecordingGateway()
        audit = RecordingAuditLog()
        mvc = MockMvcBuilders.standaloneSetup(
            NpcZoneController(gateway, audit, FixedTickClock(tick)),
        )
            .setControllerAdvice(GlobalExceptionAdvice())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(admin, null, emptyList())
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `create node-scoped zone returns 201 and writes audit row`() {
        val expected = sampleZone()
        gateway.createResult = expected

        mvc.post("/admin/worlds/${worldId.value}/npc-zones") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scope": "NODE",
                  "nodeId": 11,
                  "weights": {"GRAY_WOLF": 1},
                  "maxConcurrent": 3,
                  "respawnTicks": 100,
                  "active": true
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.scope") { value("NODE") }
            jsonPath("$.nodeId") { value(11) }
            jsonPath("$.weights.GRAY_WOLF") { value(1) }
        }

        assertEquals(NpcZoneScope.NODE, gateway.lastCreateSpec?.scope)
        assertEquals(NodeId(11L), gateway.lastCreateSpec?.nodeId)
        assertEquals(mapOf(NpcType("GRAY_WOLF") to 1), gateway.lastCreateSpec?.weights)
        val entry = audit.entries.single()
        assertEquals("npc-zone.create", entry.action)
        assertEquals(expected.zoneId.toString(), entry.targetId)
    }

    @Test
    fun `create rejects unknown NPC type with 400`() {
        gateway.createError = NpcZoneAdminError.UnknownNpcTypes(setOf(NpcType("PHANTOM")))

        mvc.post("/admin/worlds/${worldId.value}/npc-zones") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"NODE","nodeId":11,"weights":{"PHANTOM":1},"maxConcurrent":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("unknown NPC types: PHANTOM") }
        }

        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `create rejects node outside world with 404`() {
        gateway.createError = NpcZoneAdminError.WorldMismatch(WorldId(worldId.value), WorldId(99L))

        mvc.post("/admin/worlds/${worldId.value}/npc-zones") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"NODE","nodeId":11,"weights":{"GRAY_WOLF":1},"maxConcurrent":1}"""
        }.andExpect { status { isNotFound() } }

        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `list returns zones for world`() {
        gateway.listResult = listOf(sampleZone())

        mvc.get("/admin/worlds/${worldId.value}/npc-zones").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
    }

    @Test
    fun `patch updates weights and writes audit row`() {
        val zoneId = UUID.randomUUID()
        gateway.patchResult = sampleZone().copy(zoneId = zoneId, weights = mapOf(NpcType("WILD_BOAR") to 2))

        mvc.patch("/admin/worlds/${worldId.value}/npc-zones/$zoneId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"weights":{"WILD_BOAR":2}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.weights.WILD_BOAR") { value(2) }
        }

        assertEquals(mapOf(NpcType("WILD_BOAR") to 2), (gateway.lastPatch?.weights as? dev.gvart.genesara.world.MaybeSet.Set)?.value)
        val entry = audit.entries.single()
        assertEquals("npc-zone.patch", entry.action)
        assertEquals(listOf("weights"), entry.payload["changedFields"])
    }

    @Test
    fun `patch rejects when zone belongs to another world`() {
        val zoneId = UUID.randomUUID()
        gateway.patchResult = sampleZone().copy(zoneId = zoneId, worldId = WorldId(99L))

        mvc.patch("/admin/worlds/${worldId.value}/npc-zones/$zoneId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"maxConcurrent":5}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `patch returns 404 when gateway reports not found`() {
        val zoneId = UUID.randomUUID()
        gateway.patchError = NpcZoneAdminError.NotFound(zoneId)

        mvc.patch("/admin/worlds/${worldId.value}/npc-zones/$zoneId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"active":false}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `delete removes zone and writes audit row`() {
        val zoneId = UUID.randomUUID()
        val existing = sampleZone().copy(zoneId = zoneId)
        gateway.listResult = listOf(existing)
        gateway.deleteResult = true

        mvc.delete("/admin/worlds/${worldId.value}/npc-zones/$zoneId")
            .andExpect { status { isNoContent() } }

        assertEquals(zoneId, gateway.lastDeleteId)
        assertEquals("npc-zone.delete", audit.entries.single().action)
    }

    @Test
    fun `delete returns 404 when zone not in world`() {
        val zoneId = UUID.randomUUID()
        gateway.listResult = emptyList()

        mvc.delete("/admin/worlds/${worldId.value}/npc-zones/$zoneId")
            .andExpect { status { isNotFound() } }

        assertTrue(audit.entries.isEmpty())
    }

    private fun sampleZone() = NpcZone(
        zoneId = UUID.randomUUID(),
        worldId = worldId,
        scope = NpcZoneScope.NODE,
        regionId = null,
        nodeId = NodeId(11L),
        weights = mapOf(NpcType("GRAY_WOLF") to 1),
        maxConcurrent = 3,
        respawnTicks = 100,
        active = true,
        createdBy = admin.id.id,
        createdAtTick = tick,
    )

    private class RecordingGateway : NpcZoneAdminGateway {
        var createResult: NpcZone? = null
        var createError: RuntimeException? = null
        var lastCreateSpec: NpcZoneCreateSpec? = null
        var listResult: List<NpcZone> = emptyList()
        var patchResult: NpcZone? = null
        var patchError: RuntimeException? = null
        var lastPatch: NpcZonePatch? = null
        var deleteResult = true
        var lastDeleteId: UUID? = null

        override fun list(worldId: WorldId): List<NpcZone> = listResult

        override fun create(spec: NpcZoneCreateSpec): NpcZone {
            lastCreateSpec = spec
            createError?.let { throw it }
            return createResult ?: error("createResult not set")
        }

        override fun patch(zoneId: UUID, patch: NpcZonePatch): NpcZone {
            lastPatch = patch
            patchError?.let { throw it }
            return patchResult ?: error("patchResult not set")
        }

        override fun delete(zoneId: UUID): Boolean {
            lastDeleteId = zoneId
            return deleteResult
        }
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
                occurredAt = Instant.EPOCH,
            )
            return entries.last().seq
        }
        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = error("unused")
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
