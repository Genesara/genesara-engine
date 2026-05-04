package dev.gvart.genesara.world.internal.buildings

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
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

@Testcontainers
class JooqBuildingsStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("buildings_it")
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

    private lateinit var store: JooqBuildingsStore
    private val node1 = NodeId(1L)
    private val node2 = NodeId(2L)
    private val node3 = NodeId(3L)
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(NODE_BUILDINGS).cascade().execute()
        store = JooqBuildingsStore(dsl)
    }

    @Test
    fun `insert and findById round-trip a full UNDER_CONSTRUCTION instance`() {
        val b = sampleBuilding(progress = 1, totalSteps = 5, status = BuildingStatus.UNDER_CONSTRUCTION)
        store.insert(b)

        assertEquals(b, store.findById(b.instanceId))
    }

    @Test
    fun `findById returns null for a missing instance`() {
        assertNull(store.findById(UUID.randomUUID()))
    }

    @Test
    fun `findInProgress returns the matching agent's in-progress instance`() {
        val mine = sampleBuilding(progress = 2, totalSteps = 5, status = BuildingStatus.UNDER_CONSTRUCTION)
        store.insert(mine)

        assertEquals(mine, store.findInProgress(node1, agent, BuildingType.CAMPFIRE))
    }

    @Test
    fun `findInProgress is scoped per agent — another agent's in-progress instance is invisible`() {
        val theirs = sampleBuilding(
            agent = otherAgent,
            progress = 2,
            totalSteps = 5,
            status = BuildingStatus.UNDER_CONSTRUCTION,
        )
        store.insert(theirs)

        assertNull(store.findInProgress(node1, agent, BuildingType.CAMPFIRE))
    }

    @Test
    fun `findInProgress is scoped per type — same agent same node different type is invisible`() {
        val campfire = sampleBuilding(
            type = BuildingType.CAMPFIRE,
            progress = 2,
            totalSteps = 5,
            status = BuildingStatus.UNDER_CONSTRUCTION,
        )
        store.insert(campfire)

        assertNull(store.findInProgress(node1, agent, BuildingType.STORAGE_CHEST))
    }

    @Test
    fun `findInProgress excludes ACTIVE rows — completed buildings don't shadow new starts`() {
        val finished = sampleBuilding(
            type = BuildingType.CAMPFIRE,
            progress = 5,
            totalSteps = 5,
            status = BuildingStatus.ACTIVE,
        )
        store.insert(finished)

        assertNull(store.findInProgress(node1, agent, BuildingType.CAMPFIRE))
    }

    @Test
    fun `listAtNode returns every status, ordered by instance_id for stability`() {
        val ids = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
        ids.forEach { id ->
            store.insert(sampleBuilding(instanceId = id, progress = 5, totalSteps = 5, status = BuildingStatus.ACTIVE))
        }

        assertEquals(ids.sorted(), store.listAtNode(node1).map { it.instanceId })
    }

    @Test
    fun `listByNodes batches across nodes and omits nodes with no buildings`() {
        store.insert(sampleBuilding(nodeId = node1, type = BuildingType.CAMPFIRE, progress = 5, totalSteps = 5, status = BuildingStatus.ACTIVE))
        store.insert(sampleBuilding(nodeId = node1, type = BuildingType.SHELTER, progress = 1, totalSteps = 12, status = BuildingStatus.UNDER_CONSTRUCTION))
        store.insert(sampleBuilding(nodeId = node2, type = BuildingType.WORKBENCH, progress = 5, totalSteps = 10, status = BuildingStatus.UNDER_CONSTRUCTION))

        val byNode = store.listByNodes(setOf(node1, node2, node3))

        assertEquals(2, byNode[node1]?.size)
        assertEquals(1, byNode[node2]?.size)
        assertEquals(null, byNode[node3])
    }

    @Test
    fun `listByNodes with empty input returns an empty map without hitting the DB`() {
        assertEquals(emptyMap(), store.listByNodes(emptySet()))
    }

    @Test
    fun `advanceProgress increments steps and stamps the tick`() {
        val b = sampleBuilding(progress = 3, totalSteps = 5, lastProgressTick = 10L, status = BuildingStatus.UNDER_CONSTRUCTION)
        store.insert(b)

        val advanced = assertNotNull(store.advanceProgress(b.instanceId, newProgress = 4, asOfTick = 12L))
        assertEquals(4, advanced.progressSteps)
        assertEquals(12L, advanced.lastProgressTick)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, advanced.status)
    }

    @Test
    fun `advanceProgress refuses to roll back an already-ACTIVE row`() {
        val b = sampleBuilding(progress = 5, totalSteps = 5, status = BuildingStatus.ACTIVE)
        store.insert(b)

        assertNull(store.advanceProgress(b.instanceId, newProgress = 4, asOfTick = 12L))
    }

    @Test
    fun `complete flips status and progress in one statement`() {
        val b = sampleBuilding(progress = 4, totalSteps = 5, lastProgressTick = 10L, status = BuildingStatus.UNDER_CONSTRUCTION)
        store.insert(b)

        val active = assertNotNull(store.complete(b.instanceId, asOfTick = 12L))
        assertEquals(BuildingStatus.ACTIVE, active.status)
        assertEquals(b.totalSteps, active.progressSteps)
        assertEquals(12L, active.lastProgressTick)
    }

    @Test
    fun `complete is a no-op for an already-ACTIVE row`() {
        // The `WHERE status = UNDER_CONSTRUCTION` predicate keeps `complete`
        // idempotent for a future Phase 3 collaborative-build flow where two
        // agents could race to finish the last step; the second call gets null
        // and the reducer treats the row as already done.
        val b = sampleBuilding(progress = 5, totalSteps = 5, status = BuildingStatus.ACTIVE)
        store.insert(b)

        assertNull(store.complete(b.instanceId, asOfTick = 12L))
    }

    @Test
    fun `insert with duplicate instance_id throws DataAccessException on PK conflict`() {
        val b = sampleBuilding()
        store.insert(b)

        assertFailsWith<DataAccessException> { store.insert(b) }
    }

    @Test
    fun `status CHECK rejects an unknown status string at the DB level`() {
        // Bypass the Kotlin enum to confirm the schema fence — defense against
        // a future hand-fixed row poisoning BuildingStatus.valueOf on read.
        val b = sampleBuilding()
        store.insert(b)

        assertFailsWith<DataAccessException> {
            dsl.update(NODE_BUILDINGS)
                .set(NODE_BUILDINGS.STATUS, "GIBBERISH")
                .where(NODE_BUILDINGS.INSTANCE_ID.eq(b.instanceId))
                .execute()
        }
    }

    @Test
    fun `biconditional CHECK rejects ACTIVE with incomplete progress`() {
        val b = sampleBuilding(progress = 3, totalSteps = 5, status = BuildingStatus.UNDER_CONSTRUCTION)
        store.insert(b)

        assertFailsWith<DataAccessException> {
            dsl.update(NODE_BUILDINGS)
                .set(NODE_BUILDINGS.STATUS, BuildingStatus.ACTIVE.name)
                .where(NODE_BUILDINGS.INSTANCE_ID.eq(b.instanceId))
                .execute()
        }
    }

    @Test
    fun `biconditional CHECK rejects UNDER_CONSTRUCTION at full progress`() {
        val b = sampleBuilding(progress = 5, totalSteps = 5, status = BuildingStatus.ACTIVE)
        store.insert(b)

        assertFailsWith<DataAccessException> {
            dsl.update(NODE_BUILDINGS)
                .set(NODE_BUILDINGS.STATUS, BuildingStatus.UNDER_CONSTRUCTION.name)
                .where(NODE_BUILDINGS.INSTANCE_ID.eq(b.instanceId))
                .execute()
        }
    }

    @Test
    fun `hp CHECK rejects hp_current greater than hp_max at the DB level`() {
        val b = sampleBuilding(hp = 30)
        store.insert(b)

        assertFailsWith<DataAccessException> {
            dsl.update(NODE_BUILDINGS)
                .set(NODE_BUILDINGS.HP_CURRENT, 999)
                .where(NODE_BUILDINGS.INSTANCE_ID.eq(b.instanceId))
                .execute()
        }
    }

    private fun sampleBuilding(
        instanceId: UUID = UUID.randomUUID(),
        nodeId: NodeId = node1,
        type: BuildingType = BuildingType.CAMPFIRE,
        agent: AgentId = this.agent,
        progress: Int = 1,
        totalSteps: Int = 5,
        status: BuildingStatus = BuildingStatus.UNDER_CONSTRUCTION,
        builtAtTick: Long = 1L,
        lastProgressTick: Long = 1L,
        hp: Int = 30,
    ): Building = Building(
        instanceId = instanceId,
        nodeId = nodeId,
        type = type,
        status = status,
        builtByAgentId = agent,
        builtAtTick = builtAtTick,
        lastProgressTick = lastProgressTick,
        progressSteps = if (status == BuildingStatus.ACTIVE) totalSteps else progress,
        totalSteps = totalSteps,
        hpCurrent = hp,
        hpMax = hp,
    )
}
