package dev.gvart.genesara.world.internal.buildings

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDING_BARS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqBuildingBarsStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("building_bars_it")
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

    private lateinit var store: JooqBuildingBarsStore
    private val instanceId = UUID.randomUUID()
    private val carpentry = SkillId("CARPENTRY")
    private val survival = SkillId("SURVIVAL")

    @BeforeEach
    fun reset() {
        dsl.truncate(NODE_BUILDING_BARS).cascade().execute()
        store = JooqBuildingBarsStore(dsl)
    }

    @Test
    fun `insertAll persists every bar with default progress 0`() {
        val bars = listOf(
            BuildingBar(instanceId, carpentry, progressSteps = 0, totalSteps = 8),
            BuildingBar(instanceId, survival, progressSteps = 0, totalSteps = 6),
        )

        store.insertAll(bars)

        val readBack = store.barsByInstance(instanceId)
        assertEquals(2, readBack.size)
        assertEquals(bars.toSet(), readBack.toSet())
    }

    @Test
    fun `insertAll with empty list short-circuits without touching the DB`() {
        store.insertAll(emptyList())
        assertEquals(emptyList(), store.barsByInstance(instanceId))
    }

    @Test
    fun `insertAll persists non-zero starting progress (first-step bar lands at 1)`() {
        val bars = listOf(
            BuildingBar(instanceId, carpentry, progressSteps = 1, totalSteps = 8),
            BuildingBar(instanceId, survival, progressSteps = 0, totalSteps = 6),
        )

        store.insertAll(bars)

        val readBack = store.barsByInstance(instanceId)
        assertEquals(1, readBack.first { it.skill == carpentry }.progressSteps)
        assertEquals(0, readBack.first { it.skill == survival }.progressSteps)
    }

    @Test
    fun `barsByInstance returns rows ordered by skill_id`() {
        store.insertAll(
            listOf(
                BuildingBar(instanceId, survival, progressSteps = 2, totalSteps = 6),
                BuildingBar(instanceId, carpentry, progressSteps = 3, totalSteps = 8),
            ),
        )

        val rows = store.barsByInstance(instanceId)
        assertEquals(carpentry, rows[0].skill)
        assertEquals(survival, rows[1].skill)
    }

    @Test
    fun `barsByInstances batches via IN clause and groups by instance`() {
        val secondInstance = UUID.randomUUID()
        store.insertAll(
            listOf(
                BuildingBar(instanceId, carpentry, progressSteps = 1, totalSteps = 8),
                BuildingBar(secondInstance, carpentry, progressSteps = 5, totalSteps = 10),
            ),
        )

        val grouped = store.barsByInstances(setOf(instanceId, secondInstance))
        assertEquals(setOf(instanceId, secondInstance), grouped.keys)
        assertEquals(1, grouped[instanceId]?.single()?.progressSteps)
        assertEquals(5, grouped[secondInstance]?.single()?.progressSteps)
    }

    @Test
    fun `barsByInstances with empty input returns an empty map without hitting the DB`() {
        assertEquals(emptyMap(), store.barsByInstances(emptySet()))
    }

    @Test
    fun `advanceBar increments progress and returns the updated row`() {
        store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 3, totalSteps = 8)))

        val advanced = assertNotNull(store.advanceBar(instanceId, carpentry))
        assertEquals(4, advanced.progressSteps)
        assertEquals(8, advanced.totalSteps)
    }

    @Test
    fun `advanceBar returns null when the bar is already filled, leaving the row untouched`() {
        store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 8, totalSteps = 8)))

        assertNull(store.advanceBar(instanceId, carpentry))
        assertEquals(8, store.barsByInstance(instanceId).single().progressSteps)
    }

    @Test
    fun `advanceBar returns null when the bar does not exist`() {
        assertNull(store.advanceBar(instanceId, carpentry))
    }

    @Test
    fun `advanceBar to the final step is allowed (progress == total)`() {
        store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 7, totalSteps = 8)))

        val advanced = assertNotNull(store.advanceBar(instanceId, carpentry))
        assertEquals(8, advanced.progressSteps)
    }

    @Test
    fun `duplicate (instance_id, skill_id) insert fails the PK constraint`() {
        store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 0, totalSteps = 8)))

        assertFailsWith<DataAccessException> {
            store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 0, totalSteps = 8)))
        }
    }

    @Test
    fun `negative total_steps insert fails the CHECK constraint`() {
        assertFailsWith<DataAccessException> {
            store.insertAll(listOf(BuildingBar(instanceId, carpentry, progressSteps = 0, totalSteps = -1)))
        }
    }

    @Test
    fun `barsByInstance returns empty list for an instance with no rows`() {
        assertTrue(store.barsByInstance(UUID.randomUUID()).isEmpty())
    }
}
