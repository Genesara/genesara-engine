package dev.gvart.genesara.api.internal.rest.admin.audit

import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AdminAuditControllerTest {

    private val admin = AdminId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-26T12:00:00Z")
    private lateinit var stub: StubAuditLog
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        stub = StubAuditLog()
        mvc = MockMvcBuilders.standaloneSetup(AdminAuditController(stub))
            .setControllerAdvice(GlobalExceptionAdvice())
            .build()
    }

    @Test
    fun `list returns the stub entries with cursor pointing at the last seq`() {
        stub.entries = listOf(
            entry(seq = 11, action = "npc.spawn", targetId = "42"),
            entry(seq = 12, action = "npc.kill", targetId = null, payload = mapOf("silent" to true)),
        )

        mvc.get("/admin/audit?after=10&limit=50").andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(2) }
            jsonPath("$.entries[0].seq") { value(11) }
            jsonPath("$.entries[0].action") { value("npc.spawn") }
            jsonPath("$.entries[0].targetId") { value("42") }
            jsonPath("$.entries[1].payload.silent") { value(true) }
            jsonPath("$.nextCursor") { value(12) }
        }

        assertEquals(10L, stub.lastAfter)
        assertEquals(50, stub.lastLimit)
    }

    @Test
    fun `list defaults after to zero and limit to one hundred`() {
        stub.entries = emptyList()

        mvc.get("/admin/audit").andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(0) }
            jsonPath("$.nextCursor") { doesNotExist() }
        }

        assertEquals(0L, stub.lastAfter)
        assertEquals(100, stub.lastLimit)
    }

    @Test
    fun `list rejects a negative after with 400`() {
        mvc.get("/admin/audit?after=-1").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("after must be >= 0") }
        }
    }

    @Test
    fun `list rejects an out-of-range limit with 400`() {
        mvc.get("/admin/audit?limit=0").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("limit must be in 1..500") }
        }
        mvc.get("/admin/audit?limit=501").andExpect {
            status { isBadRequest() }
        }
    }

    private fun entry(
        seq: Long,
        action: String,
        targetId: String?,
        payload: Map<String, Any?> = emptyMap(),
    ) = AdminAuditEntry(
        seq = seq,
        adminId = admin,
        action = action,
        target = "node",
        targetId = targetId,
        payload = payload,
        tick = 0L,
        occurredAt = now,
    )

    private class StubAuditLog : AdminAuditLog {
        var entries: List<AdminAuditEntry> = emptyList()
        var lastAfter: Long = -1
        var lastLimit: Int = -1

        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long = error("unused in this test")

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> {
            lastAfter = after
            lastLimit = limit
            return entries
        }
    }
}
