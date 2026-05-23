package dev.gvart.genesara.world.environment.internal.mount

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNTS
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNT_INVENTORY
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
import kotlin.test.assertTrue

@Testcontainers
class JooqMountInventoryStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mount_inv_it")
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

    private lateinit var store: JooqMountInventoryStore
    private lateinit var mounts: JooqMountInstanceStore
    private val nodeA = NodeId(1L)
    private val owner = AgentId(UUID.randomUUID())
    private val mountId = MountId(UUID.randomUUID())
    private val otherMountId = MountId(UUID.randomUUID())
    private val wood = ItemId("WOOD")
    private val berry = ItemId("BERRY")

    @BeforeEach
    fun reset() {
        dsl.truncate(MOUNT_INVENTORY).cascade().execute()
        dsl.truncate(MOUNTS).cascade().execute()
        store = JooqMountInventoryStore(dsl)
        mounts = JooqMountInstanceStore(dsl)
        mounts.insert(sampleMount(mountId))
        mounts.insert(sampleMount(otherMountId))
    }

    @Test
    fun `byMount returns empty map for a mount with no rows`() {
        assertEquals(emptyMap(), store.byMount(mountId))
    }

    @Test
    fun `increment inserts a fresh row and re-increments aggregates`() {
        store.increment(mountId, wood, 5)
        assertEquals(mapOf(wood to 5), store.byMount(mountId))

        store.increment(mountId, wood, 3)
        assertEquals(mapOf(wood to 8), store.byMount(mountId))
    }

    @Test
    fun `byMount returns multiple stacks ordered by item`() {
        store.increment(mountId, wood, 7)
        store.increment(mountId, berry, 12)

        assertEquals(mapOf(wood to 7, berry to 12), store.byMount(mountId))
    }

    @Test
    fun `decrement partial drops quantity but keeps the row`() {
        store.increment(mountId, wood, 10)

        assertTrue(store.decrement(mountId, wood, 3))
        assertEquals(mapOf(wood to 7), store.byMount(mountId))
    }

    @Test
    fun `decrement to zero removes the row`() {
        store.increment(mountId, wood, 4)

        assertTrue(store.decrement(mountId, wood, 4))
        assertEquals(emptyMap(), store.byMount(mountId))
    }

    @Test
    fun `decrement rejects when stock is insufficient and leaves row unchanged`() {
        store.increment(mountId, wood, 2)

        assertFalse(store.decrement(mountId, wood, 5))
        assertEquals(mapOf(wood to 2), store.byMount(mountId))
    }

    @Test
    fun `decrement on missing row returns false`() {
        assertFalse(store.decrement(mountId, wood, 1))
    }

    @Test
    fun `deleteAllFor wipes only the target mount`() {
        store.increment(mountId, wood, 3)
        store.increment(mountId, berry, 9)
        store.increment(otherMountId, wood, 4)

        store.deleteAllFor(mountId)

        assertEquals(emptyMap(), store.byMount(mountId))
        assertEquals(mapOf(wood to 4), store.byMount(otherMountId))
    }

    @Test
    fun `byMounts returns a per-mount map and skips empties`() {
        store.increment(mountId, wood, 2)
        store.increment(mountId, berry, 6)
        store.increment(otherMountId, wood, 1)

        val result = store.byMounts(listOf(mountId, otherMountId))

        assertEquals(mapOf(wood to 2, berry to 6), result[mountId])
        assertEquals(mapOf(wood to 1), result[otherMountId])
    }

    @Test
    fun `byMounts empty input short-circuits to empty result`() {
        store.increment(mountId, wood, 1)
        assertEquals(emptyMap(), store.byMounts(emptyList()))
    }

    private fun sampleMount(id: MountId): Mount = Mount(
        id = id,
        type = MountType("RIDING_HORSE"),
        ownerAgentId = owner,
        nodeId = nodeA,
        hpCurrent = 80,
        hpMax = 80,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 100L,
    )
}
