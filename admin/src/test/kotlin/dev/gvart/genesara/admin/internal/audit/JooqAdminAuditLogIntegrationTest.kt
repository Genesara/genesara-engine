package dev.gvart.genesara.admin.internal.audit

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.admin.internal.jooq.tables.references.ADMIN_AUDIT_LOG
import dev.gvart.genesara.admin.internal.testsupport.AdminFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAdminAuditLogIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("admin_audit_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = AdminFlyway.pooledDataSource(postgres)
            AdminFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private lateinit var log: JooqAdminAuditLog
    private val admin = AdminId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(ADMIN_AUDIT_LOG).restartIdentity().cascade().execute()
        log = JooqAdminAuditLog(dsl, mapper)
    }

    @Test
    fun `record assigns ascending seq and persists every field`() {
        val seq1 = log.record(
            adminId = admin,
            action = "npc.spawn",
            target = "node",
            targetId = "42",
            payload = mapOf("type" to "GRAY_WOLF", "hp" to 30),
            tick = 100L,
        )
        val seq2 = log.record(
            adminId = admin,
            action = "npc.kill",
            target = "npc",
            targetId = "00000000-0000-0000-0000-000000000001",
            payload = emptyMap(),
            tick = 101L,
        )

        assertTrue(seq2 > seq1, "second seq must be strictly greater")

        val first = assertNotNull(
            dsl.selectFrom(ADMIN_AUDIT_LOG).where(ADMIN_AUDIT_LOG.SEQ.eq(seq1)).fetchOne()
        )
        assertEquals(admin.id, first[ADMIN_AUDIT_LOG.ADMIN_ID])
        assertEquals("npc.spawn", first[ADMIN_AUDIT_LOG.ACTION])
        assertEquals("node", first[ADMIN_AUDIT_LOG.TARGET])
        assertEquals("42", first[ADMIN_AUDIT_LOG.TARGET_ID])
        assertEquals(100L, first[ADMIN_AUDIT_LOG.TICK])
        assertNotNull(first[ADMIN_AUDIT_LOG.OCCURRED_AT])
    }

    @Test
    fun `readAfter returns entries ordered by seq with payload roundtrip`() {
        val payloads = listOf(
            mapOf("a" to 1, "nested" to mapOf("k" to "v")),
            mapOf("b" to listOf("x", "y", "z")),
            mapOf("c" to true, "d" to null),
        )
        payloads.forEachIndexed { i, p ->
            log.record(admin, "action.$i", "target", "$i", p, tick = i.toLong())
        }

        val page = log.readAfter(after = 0L, limit = 10)

        assertEquals(3, page.size)
        assertEquals(listOf(1L, 2L, 3L), page.map { it.seq })
        assertEquals(listOf("action.0", "action.1", "action.2"), page.map { it.action })
        assertEquals(payloads[0]["a"], page[0].payload["a"])
        assertEquals(mapOf("k" to "v"), page[0].payload["nested"])
        assertEquals(listOf("x", "y", "z"), page[1].payload["b"])
        assertEquals(true, page[2].payload["c"])
        assertNull(page[2].payload["d"])
    }

    @Test
    fun `readAfter is exclusive on cursor and limits the page size`() {
        val seqs = (1..5).map { i ->
            log.record(admin, "act.$i", "node", "$i", mapOf("i" to i), tick = i.toLong())
        }

        val firstPage = log.readAfter(after = 0L, limit = 2)
        val secondPage = log.readAfter(after = firstPage.last().seq, limit = 2)
        val thirdPage = log.readAfter(after = secondPage.last().seq, limit = 2)
        val empty = log.readAfter(after = thirdPage.last().seq, limit = 2)

        assertEquals(seqs.subList(0, 2), firstPage.map { it.seq })
        assertEquals(seqs.subList(2, 4), secondPage.map { it.seq })
        assertEquals(seqs.subList(4, 5), thirdPage.map { it.seq })
        assertEquals(emptyList(), empty)
    }

    @Test
    fun `record accepts a null targetId`() {
        val seq = log.record(
            adminId = admin,
            action = "catalog.reload",
            target = "global",
            targetId = null,
            payload = mapOf("source" to "yaml"),
            tick = 7L,
        )

        val row = assertNotNull(log.readAfter(0L, 10).singleOrNull())
        assertEquals(seq, row.seq)
        assertNull(row.targetId)
    }
}
