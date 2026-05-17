package dev.gvart.genesara.world.internal.buildings

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.BUILDING_CHEST_INVENTORY
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class JooqChestContentsStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("chest_it")
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

    private lateinit var store: JooqChestContentsStore
    private val chest = UUID.randomUUID()
    private val otherChest = UUID.randomUUID()
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")

    @BeforeEach
    fun reset() {
        dsl.truncate(BUILDING_CHEST_INVENTORY).cascade().execute()
        store = JooqChestContentsStore(dsl)
    }

    @Test
    fun `quantityOf is zero for a never-touched chest-item pair`() {
        assertEquals(0, store.quantityOf(chest, wood))
    }

    @Test
    fun `add inserts a new row and quantityOf reads it back`() {
        store.add(chest, wood, 5)
        assertEquals(5, store.quantityOf(chest, wood))
    }

    @Test
    fun `add increments an existing row atomically — repeated adds accumulate`() {
        store.add(chest, wood, 3)
        store.add(chest, wood, 4)
        assertEquals(7, store.quantityOf(chest, wood))
    }

    @Test
    fun `add is per-(chest, item) — a different item gets its own row`() {
        store.add(chest, wood, 5)
        store.add(chest, stone, 2)

        assertEquals(5, store.quantityOf(chest, wood))
        assertEquals(2, store.quantityOf(chest, stone))
    }

    @Test
    fun `add is scoped per chest — another chest's contents are invisible`() {
        store.add(chest, wood, 5)
        assertEquals(0, store.quantityOf(otherChest, wood))
    }

    @Test
    fun `add rejects a non-positive quantity`() {
        assertFailsWith<IllegalArgumentException> { store.add(chest, wood, 0) }
        assertFailsWith<IllegalArgumentException> { store.add(chest, wood, -1) }
    }

    @Test
    fun `remove decrements the quantity and returns true on success`() {
        store.add(chest, wood, 5)
        assertTrue(store.remove(chest, wood, 2))
        assertEquals(3, store.quantityOf(chest, wood))
    }

    @Test
    fun `remove returns false when the chest doesn't have enough — no row mutated`() {
        store.add(chest, wood, 2)
        assertFalse(store.remove(chest, wood, 5))
        // Original quantity preserved — partial-removal would corrupt the bag's invariants.
        assertEquals(2, store.quantityOf(chest, wood))
    }

    @Test
    fun `remove returns false when the chest has no row at all`() {
        assertFalse(store.remove(chest, wood, 1))
    }

    @Test
    fun `remove deletes the row when quantity drops to zero`() {
        store.add(chest, wood, 3)
        store.remove(chest, wood, 3)

        // Empty chests must hold zero rows so look-ups stay fast and counts
        // (e.g. "items in chest") are accurate without filtering.
        val rowCount = dsl.fetchCount(
            BUILDING_CHEST_INVENTORY,
            BUILDING_CHEST_INVENTORY.BUILDING_ID.eq(chest),
        )
        assertEquals(0, rowCount)
        assertEquals(0, store.quantityOf(chest, wood))
    }

    @Test
    fun `remove rejects a non-positive quantity`() {
        assertFailsWith<IllegalArgumentException> { store.remove(chest, wood, 0) }
        assertFailsWith<IllegalArgumentException> { store.remove(chest, wood, -1) }
    }

    @Test
    fun `contentsOf returns every row for the chest as an ItemId-keyed map`() {
        store.add(chest, wood, 5)
        store.add(chest, stone, 2)
        store.add(otherChest, wood, 99)

        assertEquals(mapOf(wood to 5, stone to 2), store.contentsOf(chest))
    }

    @Test
    fun `contentsOf returns an empty map for a never-touched chest`() {
        assertEquals(emptyMap(), store.contentsOf(chest))
    }
}
