package dev.gvart.genesara.world.environment.internal.buildings

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.world.internal.jooq.tables.references.BUILDING_GATE_STATES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqBuildingGateStateStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("gate_state_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private lateinit var store: JooqBuildingGateStateStore

    @BeforeEach
    fun reset() {
        dsl.truncate(BUILDING_GATE_STATES).cascade().execute()
        dsl.truncate(NODE_BUILDINGS).cascade().execute()
        store = JooqBuildingGateStateStore(dsl)
    }

    @Test
    fun `insertClosed sets is_open to false`() {
        val gateId = insertNodeBuilding()
        store.insertClosed(gateId)

        assertFalse(store.isOpen(gateId)!!)
    }

    @Test
    fun `toggle flips false to true`() {
        val gateId = insertNodeBuilding()
        store.insertClosed(gateId)

        val afterFirst = store.toggle(gateId)

        assertTrue(afterFirst!!)
        assertTrue(store.isOpen(gateId)!!)
    }

    @Test
    fun `toggle flips true back to false on second call`() {
        val gateId = insertNodeBuilding()
        store.insertClosed(gateId)
        store.toggle(gateId)

        val afterSecond = store.toggle(gateId)

        assertFalse(afterSecond!!)
        assertFalse(store.isOpen(gateId)!!)
    }

    @Test
    fun `isOpen returns null for an unknown gate id`() {
        assertNull(store.isOpen(UUID.randomUUID()))
    }

    @Test
    fun `toggle returns null for an unknown gate id without inserting a row`() {
        val unknown = UUID.randomUUID()

        assertNull(store.toggle(unknown))
        assertNull(store.isOpen(unknown))
    }

    @Test
    fun `deleting the node_buildings row cascades and removes the gate state row`() {
        val gateId = insertNodeBuilding()
        store.insertClosed(gateId)

        dsl.deleteFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.INSTANCE_ID.eq(gateId))
            .execute()

        assertNull(store.isOpen(gateId))
    }

    private fun insertNodeBuilding(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(NODE_BUILDINGS)
            .set(NODE_BUILDINGS.INSTANCE_ID, id)
            .set(NODE_BUILDINGS.NODE_ID, 1L)
            .set(NODE_BUILDINGS.BUILDING_TYPE, "GATE")
            .set(NODE_BUILDINGS.STATUS, "ACTIVE")
            .set(NODE_BUILDINGS.BUILT_BY_AGENT_ID, UUID.randomUUID())
            .set(NODE_BUILDINGS.BUILT_AT_TICK, 1L)
            .set(NODE_BUILDINGS.LAST_PROGRESS_TICK, 1L)
            .set(NODE_BUILDINGS.PROGRESS_STEPS, 5)
            .set(NODE_BUILDINGS.TOTAL_STEPS, 5)
            .set(NODE_BUILDINGS.HP_CURRENT, 100)
            .set(NODE_BUILDINGS.HP_MAX, 100)
            .execute()
        return id
    }
}
