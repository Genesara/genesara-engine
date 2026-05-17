package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_RELATIONSHIPS
import dev.gvart.genesara.player.internal.testsupport.PlayerFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqRelationshipsGatewayIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("relationships_it")
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

    private lateinit var gateway: JooqRelationshipsGateway

    // Pinned ids keep the `a < b` canonicalization deterministic across runs.
    private val low = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val high = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val third = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000003"))

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_RELATIONSHIPS).cascade().execute()
        gateway = JooqRelationshipsGateway(dsl)
    }

    @Test
    fun `find returns null for a pair with no history`() {
        assertNull(gateway.find(low, high))
    }

    @Test
    fun `adjust on a missing row inserts and returns the new score`() {
        val outcome = gateway.adjust(low, high, delta = -5, tick = 10L)

        assertEquals(-5, outcome.currentScore)
        val row = assertNotNull(gateway.find(low, high))
        assertEquals(-5, row.score)
        assertEquals(10L, row.lastChangedAtTick)
    }

    @Test
    fun `adjust composes additively across calls`() {
        gateway.adjust(low, high, delta = -2, tick = 1L)
        gateway.adjust(low, high, delta = -3, tick = 2L)
        val outcome = gateway.adjust(low, high, delta = +1, tick = 3L)

        assertEquals(-4, outcome.currentScore)
        assertEquals(-4, assertNotNull(gateway.find(low, high)).score)
    }

    @Test
    fun `adjust accepts pair in either argument order — canonical row is the same`() {
        gateway.adjust(low, high, delta = -10, tick = 1L)
        gateway.adjust(high, low, delta = -10, tick = 2L)

        assertEquals(-20, assertNotNull(gateway.find(low, high)).score)
        val storedRows = dsl.selectCount().from(AGENT_RELATIONSHIPS).fetchOne(0, Int::class.java)
        assertEquals(1, storedRows)
    }

    @Test
    fun `find is symmetric — returns the same row regardless of order`() {
        gateway.adjust(low, high, delta = 7, tick = 1L)
        assertEquals(gateway.find(low, high), gateway.find(high, low))
    }

    @Test
    fun `adjust clamps to upper bound`() {
        val outcome = gateway.adjust(low, high, delta = 250, tick = 1L)
        assertEquals(RelationshipsGateway.SCORE_MAX, outcome.currentScore)
    }

    @Test
    fun `adjust clamps to lower bound from a positive starting score`() {
        gateway.adjust(low, high, delta = 50, tick = 1L)
        val outcome = gateway.adjust(low, high, delta = -300, tick = 2L)
        assertEquals(RelationshipsGateway.SCORE_MIN, outcome.currentScore)
    }

    @Test
    fun `adjust rejects a pair with two identical agents`() {
        try {
            gateway.adjust(low, low, delta = 1, tick = 1L)
            error("expected IllegalArgumentException for a self-pair")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `scoresFor returns every pair the agent participates in, keyed by the other agent`() {
        gateway.adjust(low, high, delta = 5, tick = 1L)
        gateway.adjust(low, third, delta = -3, tick = 2L)
        gateway.adjust(high, third, delta = 8, tick = 3L)

        val ledger = gateway.scoresFor(low)
        assertEquals(setOf(high, third), ledger.keys)
        assertEquals(5, ledger.getValue(high).score)
        assertEquals(-3, ledger.getValue(third).score)
    }

    @Test
    fun `scoresFor returns an empty map for an agent with no relationships`() {
        gateway.adjust(low, high, delta = 5, tick = 1L)
        assertTrue(gateway.scoresFor(third).isEmpty())
    }

    @Test
    fun `adjust persists last_changed_at_tick on every write`() {
        gateway.adjust(low, high, delta = 1, tick = 100L)
        gateway.adjust(low, high, delta = 1, tick = 200L)
        assertEquals(200L, assertNotNull(gateway.find(low, high)).lastChangedAtTick)
    }

    @Test
    fun `adjustMany applies the same delta to every (anchor, other) pair in one round trip`() {
        gateway.adjustMany(low, listOf(high, third), delta = -5, tick = 7L)

        assertEquals(-5, assertNotNull(gateway.find(low, high)).score)
        assertEquals(-5, assertNotNull(gateway.find(low, third)).score)
    }

    @Test
    fun `adjustMany dedupes the others collection and skips the anchor`() {
        // Duplicates would otherwise double-apply on the INSERT branch and cause a
        // PK conflict before the doUpdate clause kicks in.
        gateway.adjustMany(low, listOf(high, high, low, third), delta = -3, tick = 1L)

        assertEquals(-3, assertNotNull(gateway.find(low, high)).score)
        assertEquals(-3, assertNotNull(gateway.find(low, third)).score)
    }

    @Test
    fun `adjustMany composes with an existing pair via the doUpdate branch`() {
        gateway.adjust(low, high, delta = +10, tick = 1L)
        gateway.adjustMany(low, listOf(high, third), delta = -4, tick = 2L)

        assertEquals(6, assertNotNull(gateway.find(low, high)).score)
        assertEquals(-4, assertNotNull(gateway.find(low, third)).score)
    }

    @Test
    fun `adjustMany on an empty others collection is a no-op`() {
        gateway.adjustMany(low, emptyList(), delta = -10, tick = 5L)
        assertEquals(0, dsl.selectCount().from(AGENT_RELATIONSHIPS).fetchOne(0, Int::class.java))
    }

    @Test
    fun `concurrent first-touch adjusts on the same pair compose additively`() {
        // Two concurrent witnesses each apply -2 against a never-seen pair. Without
        // the ON CONFLICT atomic clamp-add, the loser would either swallow the
        // DuplicateKeyException (losing one delta) OR get a poisoned-transaction
        // error from the abort. Pin the contract: both deltas land, final score = -4.
        val tm = DataSourceTransactionManager(dataSource)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val worker = Runnable {
            val tx = tm.getTransaction(DefaultTransactionDefinition())
            try {
                start.await()
                JooqRelationshipsGateway(dsl).adjust(low, high, delta = -2, tick = 1L)
                tm.commit(tx)
            } catch (t: Throwable) {
                tm.rollback(tx)
                throw t
            } finally {
                done.countDown()
            }
        }
        val t1 = Thread(worker, "rel-1").also { it.start() }
        val t2 = Thread(worker, "rel-2").also { it.start() }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "concurrent adjusts deadlocked")
        t1.join()
        t2.join()

        assertEquals(-4, assertNotNull(gateway.find(low, high)).score)
    }
}
