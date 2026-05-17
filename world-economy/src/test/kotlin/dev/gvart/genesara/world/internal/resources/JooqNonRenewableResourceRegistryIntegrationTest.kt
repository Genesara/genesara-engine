package dev.gvart.genesara.world.internal.resources

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NON_RENEWABLE_RESOURCES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration test for [JooqNonRenewableResourceRegistry]. Verifies the Postgres-side
 * persistence that backs the "non-renewable depletion survives a Redis flush"
 * invariant — the Redis store delegates to this for items whose `regenerating = false`.
 */
@Testcontainers
class JooqNonRenewableResourceRegistryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("world_it")
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

    private lateinit var registry: JooqNonRenewableResourceRegistry
    private var nodeId: NodeId = NodeId(0L)

    private val ore = ItemId("ORE")
    private val coal = ItemId("COAL")
    private val gem = ItemId("GEM")

    @BeforeEach
    fun resetState() {
        dsl.truncate(NON_RENEWABLE_RESOURCES).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()
        nodeId = createNode()
        registry = JooqNonRenewableResourceRegistry(dsl)
    }

    @Test
    fun `snapshot returns empty when no rows exist`() {
        assertEquals(emptyMap(), registry.snapshot(nodeId, listOf(ore, coal)))
    }

    @Test
    fun `upsert inserts on first call and updates on subsequent calls`() {
        registry.upsert(nodeId, ore, quantity = 30, initialQuantity = 30)
        registry.upsert(nodeId, ore, quantity = 12, initialQuantity = 30)

        val snapshot = registry.snapshot(nodeId, listOf(ore))
        val state = assertNotNull(snapshot[ore])
        assertEquals(12, state.quantity)
        assertEquals(30, state.initialQuantity)
    }

    @Test
    fun `initial_quantity is locked on first insert and ignored by later upserts`() {
        // Even if a later call passes a different initial_quantity (e.g. respawn rule
        // changed between sessions), the original value must stick.
        registry.upsert(nodeId, ore, quantity = 30, initialQuantity = 30)
        registry.upsert(nodeId, ore, quantity = 5, initialQuantity = 9999)

        val state = assertNotNull(registry.snapshot(nodeId, listOf(ore))[ore])
        assertEquals(5, state.quantity)
        assertEquals(30, state.initialQuantity)
    }

    @Test
    fun `snapshot scopes to the requested item set`() {
        registry.upsert(nodeId, ore, quantity = 10, initialQuantity = 30)
        registry.upsert(nodeId, coal, quantity = 5, initialQuantity = 20)
        registry.upsert(nodeId, gem, quantity = 1, initialQuantity = 5)

        val snapshot = registry.snapshot(nodeId, listOf(ore, gem))
        assertEquals(setOf(ore, gem), snapshot.keys)
    }

    @Test
    fun `snapshot is per-node — rows for other nodes are not returned`() {
        val other = createNode()
        registry.upsert(nodeId, ore, quantity = 10, initialQuantity = 30)
        registry.upsert(other, ore, quantity = 5, initialQuantity = 30)

        val mine = registry.snapshot(nodeId, listOf(ore))
        assertEquals(10, mine[ore]?.quantity)
    }

    @Test
    fun `node-cascade deletion drops the depletion rows`() {
        registry.upsert(nodeId, ore, quantity = 10, initialQuantity = 30)
        // Cascade via FK ON DELETE CASCADE — admin tools may delete a node and the
        // depletion overlay must follow rather than orphan rows.
        dsl.deleteFrom(NODES).where(NODES.ID.eq(nodeId.value)).execute()

        assertEquals(emptyMap(), registry.snapshot(nodeId, listOf(ore)))
    }

    private fun createNode(): NodeId {
        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "test-world-${System.nanoTime()}")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!
        val regionId = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!
        val nodeId = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "FOREST")
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
        return NodeId(nodeId)
    }
}
