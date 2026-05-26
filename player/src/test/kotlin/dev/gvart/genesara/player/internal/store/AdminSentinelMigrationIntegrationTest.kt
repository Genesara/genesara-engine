package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AdminSentinel
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.testsupport.PlayerFlyway
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Testcontainers
class AdminSentinelMigrationIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("admin_sentinel_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = PlayerFlyway.pooledDataSource(postgres)
            PlayerFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    @Test
    fun `V211 inserts the sentinel agent at the constant UUID with deterministic defaults`() {
        val row = assertNotNull(
            dsl.selectFrom(AGENTS).where(AGENTS.ID.eq(AdminSentinel.agentId.id)).fetchOne()
        )

        assertEquals(AdminSentinel.agentId.id, row[AGENTS.ID])
        assertEquals("admin-sentinel", row[AGENTS.NAME])
    }

    @Test
    fun `re-running V211 does not duplicate the sentinel row`() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/player")
            .load()
            .migrate()

        val count = dsl.selectCount()
            .from(AGENTS)
            .where(AGENTS.ID.eq(AdminSentinel.agentId.id))
            .fetchOne(0, Long::class.java)
        assertEquals(1L, count)
    }
}
