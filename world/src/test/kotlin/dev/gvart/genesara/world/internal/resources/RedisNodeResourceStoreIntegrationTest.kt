package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises [RedisNodeResourceStore] against a real Redis instance via Testcontainers.
 * Mirrors the test cases for the prior Postgres-backed impl so the behavioural contract
 * stays identical: lazy regen on read, idempotent seed, atomic decrement, carryover
 * preservation across decrement.
 */
@Testcontainers
class RedisNodeResourceStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val nodeId = NodeId(1L)

    private val wood = ItemId("WOOD")
    private val ore = ItemId("ORE")
    private val berry = ItemId("BERRY")

    private val items = StubItemLookup(
        mapOf(
            wood to Item(wood, "Wood", "", ItemCategory.RESOURCE, 800, 200, regenerating = true, regenIntervalTicks = 10, regenAmount = 3),
            ore to Item(ore, "Ore", "", ItemCategory.RESOURCE, 2000, 50, regenerating = false, regenIntervalTicks = 0, regenAmount = 0),
            berry to Item(
                berry, "Berries", "", ItemCategory.RESOURCE, 50, 200,
                consumable = ConsumableEffect(Gauge.HUNGER, 20),
                regenerating = true, regenIntervalTicks = 5, regenAmount = 2,
            ),
        ),
    )

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var registry: InMemoryNonRenewableRegistry
    private lateinit var store: RedisNodeResourceStore

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        // Wipe any state from prior tests.
        template.connectionFactory!!.connection.serverCommands().flushDb()
        registry = InMemoryNonRenewableRegistry()
        store = RedisNodeResourceStore(template, items, registry)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `seed plus read round-trips initial rows`() {
        store.seed(
            listOf(
                InitialResourceRow(nodeId, wood, 100),
                InitialResourceRow(nodeId, ore, 50),
            ),
            tick = 0L,
        )

        val view = store.read(nodeId, tick = 0L)
        assertEquals(100, view.quantityOf(wood))
        assertEquals(50, view.quantityOf(ore))
    }

    @Test
    fun `seed is idempotent — re-seed does not overwrite existing rows`() {
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 0L)
        store.decrement(nodeId, wood, amount = 25, tick = 1L)
        // Now wood is at 75. A second seed at a different value must NOT overwrite.
        store.seed(listOf(InitialResourceRow(nodeId, wood, 999)), tick = 1L)

        assertEquals(75, store.availability(nodeId, wood, tick = 1L)?.quantity)
    }

    @Test
    fun `decrement reduces quantity`() {
        store.seed(listOf(InitialResourceRow(nodeId, wood, 50)), tick = 0L)

        store.decrement(nodeId, wood, amount = 10, tick = 7L)

        val cell = assertNotNull(store.availability(nodeId, wood, tick = 7L))
        assertEquals(40, cell.quantity)
        assertEquals(50, cell.initialQuantity)
    }

    @Test
    fun `decrement throws when amount exceeds quantity and leaves the row untouched`() {
        store.seed(listOf(InitialResourceRow(nodeId, wood, 5)), tick = 0L)

        runCatching { store.decrement(nodeId, wood, amount = 10, tick = 1L) }
            .also { assertEquals(true, it.isFailure) }
        assertEquals(5, store.availability(nodeId, wood, tick = 1L)?.quantity)
    }

    @Test
    fun `availability returns null when no cell exists for the item`() {
        store.seed(listOf(InitialResourceRow(nodeId, wood, 50)), tick = 0L)

        assertNull(store.availability(nodeId, ore, tick = 0L))
    }

    @Test
    fun `lazy regen tops up regenerating items based on elapsed intervals`() {
        // WOOD: regenIntervalTicks=10, regenAmount=3.
        // Seed at tick 0 with quantity 100, then decrement to 50. At tick 25 (15 ticks
        // after the last write at tick 0), one full interval has elapsed → +3 → quantity 53.
        // Wait — 25/10 = 2 intervals → +6 → quantity 56.
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 0L)
        store.decrement(nodeId, wood, amount = 50, tick = 0L)

        val cell = assertNotNull(store.availability(nodeId, wood, tick = 25L))
        assertEquals(56, cell.quantity)
    }

    @Test
    fun `regen advances last_regen_at_tick by full intervals not by current tick`() {
        // Seed and decrement, then read at tick 25 (2 intervals consumed). The next read
        // at tick 30 should add only one more interval (3), proving the carryover works.
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 0L)
        store.decrement(nodeId, wood, amount = 50, tick = 0L)

        store.availability(nodeId, wood, tick = 25L) // applies 2 intervals → 56, last=20
        val cell = assertNotNull(store.availability(nodeId, wood, tick = 30L))
        // tick 30 - last 20 = 10, exactly one more interval → +3.
        assertEquals(59, cell.quantity)
    }

    @Test
    fun `decrement preserves the regen carryover — gather doesn't burn unrealised regen credit`() {
        // Critical-1 regression test from the slice review.
        //
        // Setup: seed q=100 → decrement 50 → q=50, lastRegen=0.
        //        availability(tick=14) advances by 1 event → q=53, lastRegen=10.
        //        decrement(amount=1, tick=14) → q=52. lastRegen MUST stay at 10.
        //
        // Read at tick 23 — chosen to distinguish the bug:
        //   With the fix:  23 - lastRegen(10) = 13 ≥ interval(10) → 1 event → q=55
        //   With the bug:  23 - lastRegen(14) =  9 < interval(10) → 0 events → q=52
        //
        // (Reading at tick 25 would NOT distinguish — both produce q=55 because the
        // bug's delayed event still fires by then.)
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 0L)
        store.decrement(nodeId, wood, amount = 50, tick = 0L)

        store.availability(nodeId, wood, tick = 14L) // → 53, lastRegen=10
        store.decrement(nodeId, wood, amount = 1, tick = 14L) // → 52, lastRegen MUST stay 10

        val cell = assertNotNull(store.availability(nodeId, wood, tick = 23L))
        assertEquals(55, cell.quantity, "carryover must survive decrement; got ${cell.quantity}")
    }

    @Test
    fun `non-regenerating items stay depleted forever`() {
        store.seed(listOf(InitialResourceRow(nodeId, ore, 20)), tick = 0L)
        store.decrement(nodeId, ore, amount = 20, tick = 0L)

        val cell = assertNotNull(store.availability(nodeId, ore, tick = 1_000_000L))
        assertEquals(0, cell.quantity)
    }

    @Test
    fun `regen caps at initial_quantity — rich nodes don't grow unbounded`() {
        // BERRY: regen 2 every 5 ticks. Seed at 100/100, decrement 5, advance by a huge
        // tick → regen attempts to refund hundreds, capped at 100.
        store.seed(listOf(InitialResourceRow(nodeId, berry, 100)), tick = 0L)
        store.decrement(nodeId, berry, amount = 5, tick = 0L)

        val cell = assertNotNull(store.availability(nodeId, berry, tick = 10_000L))
        assertEquals(100, cell.quantity)
    }

    @Test
    fun `read groups multi-item nodes correctly`() {
        store.seed(
            listOf(
                InitialResourceRow(nodeId, wood, 100),
                InitialResourceRow(nodeId, ore, 30),
                InitialResourceRow(nodeId, berry, 50),
            ),
            tick = 0L,
        )

        val view = store.read(nodeId, tick = 0L)
        assertEquals(100, view.quantityOf(wood))
        assertEquals(30, view.quantityOf(ore))
        assertEquals(50, view.quantityOf(berry))
        assertEquals(3, view.entries.size)
    }

    @Test
    fun `non-renewable depletion survives a Redis flush — re-paint cannot resurrect the deposit`() {
        // Mine ORE to zero, simulate a Redis flush, re-paint via seed → cell shows q=0.
        store.seed(listOf(InitialResourceRow(nodeId, ore, 30)), tick = 0L)
        store.decrement(nodeId, ore, amount = 30, tick = 0L)
        assertEquals(0, store.availability(nodeId, ore, tick = 0L)?.quantity)

        // Wipe Redis to mimic a flush / server restart with no Redis persistence.
        template.connectionFactory!!.connection.serverCommands().flushDb()

        // Sanity: the cell really is gone.
        assertNull(store.availability(nodeId, ore, tick = 1L))

        // Admin re-paints the region. The spawn roll wants to give us a fresh 30 ORE,
        // but the registry remembers the depletion and we end up at q=0 again.
        store.seed(listOf(InitialResourceRow(nodeId, ore, 30)), tick = 1L)

        val cell = assertNotNull(store.availability(nodeId, ore, tick = 1L))
        assertEquals(0, cell.quantity, "non-renewable must NOT be resurrected by re-paint")
        assertEquals(30, cell.initialQuantity, "initial_quantity should reflect the original roll")
    }

    @Test
    fun `partially-mined non-renewable also survives a flush`() {
        // ORE at 30; mine 20, leaving 10. Flush, re-paint → cell shows q=10, not 30.
        store.seed(listOf(InitialResourceRow(nodeId, ore, 30)), tick = 0L)
        store.decrement(nodeId, ore, amount = 20, tick = 0L)

        template.connectionFactory!!.connection.serverCommands().flushDb()
        store.seed(listOf(InitialResourceRow(nodeId, ore, 30)), tick = 1L)

        val cell = assertNotNull(store.availability(nodeId, ore, tick = 1L))
        assertEquals(10, cell.quantity, "partially-mined non-renewable must keep its remaining quantity")
    }

    @Test
    fun `renewables ARE allowed to come back after a flush — they're not in the registry`() {
        // Mine WOOD to zero, flush, re-paint → cell shows the freshly-rolled quantity.
        // This is intentional: renewables are ephemeral by design and a flush is a
        // "world reset" for them.
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 0L)
        store.decrement(nodeId, wood, amount = 100, tick = 0L)

        template.connectionFactory!!.connection.serverCommands().flushDb()
        store.seed(listOf(InitialResourceRow(nodeId, wood, 100)), tick = 1L)

        val cell = assertNotNull(store.availability(nodeId, wood, tick = 1L))
        assertEquals(100, cell.quantity, "renewable items should re-spawn at full quantity after a flush")
    }

    @Test
    fun `decrement of a renewable does not write to the non-renewable registry`() {
        store.seed(listOf(InitialResourceRow(nodeId, wood, 50)), tick = 0L)
        store.decrement(nodeId, wood, amount = 5, tick = 0L)

        assertEquals(0, registry.writes.size, "renewables must not pollute the non-renewable registry")
    }

    @Test
    fun `decrement of a non-renewable writes the new state to the registry`() {
        store.seed(listOf(InitialResourceRow(nodeId, ore, 20)), tick = 0L)
        store.decrement(nodeId, ore, amount = 5, tick = 0L)

        assertEquals(1, registry.writes.size)
        assertEquals(15, registry.writes.last().quantity)
        assertEquals(20, registry.writes.last().initialQuantity)
    }

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    /**
     * In-memory stand-in for the real Postgres-backed registry. Behaves like a
     * map keyed on `(nodeId, item)`; survives the test's simulated Redis flush
     * because it's a separate JVM-side container — exactly mirroring the real
     * production split where the registry lives in Postgres while the cell lives
     * in Redis.
     */
    private class InMemoryNonRenewableRegistry : NonRenewableResourceRegistry {
        private data class Key(val nodeId: NodeId, val item: ItemId)
        private val storage = mutableMapOf<Key, NonRenewableState>()
        val writes: List<NonRenewableState> get() = writeLog
        private val writeLog = mutableListOf<NonRenewableState>()

        override fun snapshot(nodeId: NodeId, items: Collection<ItemId>): Map<ItemId, NonRenewableState> {
            val out = mutableMapOf<ItemId, NonRenewableState>()
            for (item in items) {
                storage[Key(nodeId, item)]?.let { out[item] = it }
            }
            return out
        }

        override fun upsert(nodeId: NodeId, item: ItemId, quantity: Int, initialQuantity: Int) {
            val key = Key(nodeId, item)
            val existing = storage[key]
            val next = NonRenewableState(
                quantity = quantity,
                // initial_quantity is set on first insert and never updated thereafter,
                // mirroring the Postgres ON CONFLICT clause.
                initialQuantity = existing?.initialQuantity ?: initialQuantity,
            )
            storage[key] = next
            writeLog += next
        }
    }
}
