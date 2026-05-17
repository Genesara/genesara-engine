package dev.gvart.genesara.world.internal.trade

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.TradeOffer
import dev.gvart.genesara.world.TradeStatus
import dev.gvart.genesara.world.internal.jooq.tables.references.TRADE_OFFERS
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
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqTradeStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("trade_it")
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

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private lateinit var store: JooqTradeStore

    private val offerer = AgentId(UUID.randomUUID())
    private val recipient = AgentId(UUID.randomUUID())
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val berry = ItemId("BERRY")

    @BeforeEach
    fun reset() {
        dsl.truncate(TRADE_OFFERS).cascade().execute()
        store = JooqTradeStore(dsl, mapper)
    }

    @Test
    fun `create then find round-trips offered and requested maps`() {
        val offer = pending(
            offered = mapOf(wood to 5, stone to 2),
            requested = mapOf(berry to 7),
        )

        store.create(offer)

        val loaded = assertNotNull(store.find(offer.tradeId))
        assertEquals(offer.offered, loaded.offered)
        assertEquals(offer.requested, loaded.requested)
        assertEquals(TradeStatus.PENDING, loaded.status)
        assertEquals(offer.openedAtTick, loaded.openedAtTick)
        assertNull(loaded.resolvedAtTick)
    }

    @Test
    fun `find returns null for unknown tradeId`() {
        assertNull(store.find(UUID.randomUUID()))
    }

    @Test
    fun `findPendingForUpdate returns the row when status is PENDING`() {
        val offer = pending(mapOf(wood to 1), mapOf(stone to 1))
        store.create(offer)

        val locked = assertNotNull(store.findPendingForUpdate(offer.tradeId))
        assertEquals(offer.tradeId, locked.tradeId)
    }

    @Test
    fun `findPendingForUpdate returns null after the trade is resolved`() {
        val offer = pending(mapOf(wood to 1), mapOf(stone to 1))
        store.create(offer)

        assertTrue(store.markResolved(offer.tradeId, TradeStatus.ACCEPTED, resolvedAtTick = 9))

        assertNull(store.findPendingForUpdate(offer.tradeId))
        val terminal = assertNotNull(store.find(offer.tradeId))
        assertEquals(TradeStatus.ACCEPTED, terminal.status)
        assertEquals(9L, terminal.resolvedAtTick)
    }

    @Test
    fun `markResolved is idempotent against terminal rows — second call returns false`() {
        val offer = pending(mapOf(wood to 1), mapOf(stone to 1))
        store.create(offer)

        assertTrue(store.markResolved(offer.tradeId, TradeStatus.REJECTED, resolvedAtTick = 1))
        assertFalse(store.markResolved(offer.tradeId, TradeStatus.ACCEPTED, resolvedAtTick = 2))

        val row = assertNotNull(store.find(offer.tradeId))
        assertEquals(TradeStatus.REJECTED, row.status, "second markResolved must not overwrite a terminal row")
        assertEquals(1L, row.resolvedAtTick)
    }

    @Test
    fun `markResolved returns false for an unknown tradeId`() {
        assertFalse(store.markResolved(UUID.randomUUID(), TradeStatus.ACCEPTED, resolvedAtTick = 1))
    }

    private fun pending(offered: Map<ItemId, Int>, requested: Map<ItemId, Int>) = TradeOffer(
        tradeId = UUID.randomUUID(),
        offerer = offerer,
        recipient = recipient,
        offered = offered,
        requested = requested,
        status = TradeStatus.PENDING,
        openedAtTick = 1L,
        resolvedAtTick = null,
    )
}
