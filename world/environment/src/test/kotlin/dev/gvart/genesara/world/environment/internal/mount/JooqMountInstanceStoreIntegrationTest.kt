package dev.gvart.genesara.world.environment.internal.mount

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNTS
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqMountInstanceStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mounts_it")
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
        fun closePool() { dataSource.close() }
    }

    private lateinit var store: JooqMountInstanceStore
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)
    private val rider = AgentId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(MOUNTS).cascade().execute()
        store = JooqMountInstanceStore(dsl)
    }

    @Test
    fun `insert and findById round-trip a full mount row`() {
        val mount = sampleMount()
        store.insert(mount)
        assertEquals(mount, store.findById(mount.id))
    }

    @Test
    fun `findById returns null for an unknown mount`() {
        assertNull(store.findById(MountId(UUID.randomUUID())))
    }

    @Test
    fun `byNodes returns only mounts positioned in the requested nodes`() {
        store.insert(sampleMount(nodeId = nodeA))
        store.insert(sampleMount(nodeId = nodeA))
        store.insert(sampleMount(nodeId = nodeB))

        val result = store.byNodes(listOf(nodeA))
        assertEquals(2, result.size)
        assertTrue(result.all { it.nodeId == nodeA })
    }

    @Test
    fun `byNodes empty input short-circuits to empty result`() {
        store.insert(sampleMount())
        assertEquals(emptyList(), store.byNodes(emptyList()))
    }

    @Test
    fun `findByRider locates the mount an agent is on`() {
        val ridden = sampleMount(mountedBy = rider)
        val idle = sampleMount()
        store.insert(ridden); store.insert(idle)

        val result = assertNotNull(store.findByRider(rider))
        assertEquals(ridden.id, result.id)
    }

    @Test
    fun `findByRider returns null when no mount carries the agent`() {
        store.insert(sampleMount())
        assertNull(store.findByRider(rider))
    }

    @Test
    fun `update persists hunger fatigue node and rider mutations`() {
        val before = sampleMount(nodeId = nodeA)
        store.insert(before)

        val after = before.copy(nodeId = nodeB, hpCurrent = 30, hunger = 50, fatigue = 60, mountedByAgentId = rider)
        store.update(after)

        assertEquals(after, store.findById(before.id))
    }

    @Test
    fun `delete removes the row and reports the change`() {
        val mount = sampleMount()
        store.insert(mount)

        assertTrue(store.delete(mount.id))
        assertNull(store.findById(mount.id))
        assertEquals(false, store.delete(mount.id))
    }

    @Test
    fun `all returns every live mount irrespective of position`() {
        store.insert(sampleMount(nodeId = nodeA))
        store.insert(sampleMount(nodeId = nodeB))
        store.insert(sampleMount(nodeId = nodeA))

        assertEquals(3, store.all().size)
    }

    private fun sampleMount(
        id: MountId = MountId(UUID.randomUUID()),
        type: MountType = MountType("RIDING_HORSE"),
        nodeId: NodeId = nodeA,
        mountedBy: AgentId? = null,
    ): Mount = Mount(
        id = id,
        type = type,
        nodeId = nodeId,
        hpCurrent = 80, hpMax = 80,
        hunger = 100, hungerMax = 100,
        fatigue = 100, fatigueMax = 100,
        mountedByAgentId = mountedBy,
        tamedAtTick = 100L,
    )
}
