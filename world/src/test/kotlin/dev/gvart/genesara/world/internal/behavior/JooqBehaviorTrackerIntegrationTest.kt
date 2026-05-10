package dev.gvart.genesara.world.internal.behavior

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_ACTION_COUNTERS
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

@Testcontainers
class JooqBehaviorTrackerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("behavior_it")
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

    private lateinit var tracker: JooqBehaviorTracker
    private val agent = AgentId(UUID.randomUUID())
    private val other = AgentId(UUID.randomUUID())

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_ACTION_COUNTERS).cascade().execute()
        tracker = JooqBehaviorTracker(dsl)
    }

    @Test
    fun `record inserts at 1 for a fresh (agent, category) pair`() {
        tracker.record(agent, ActionCategory.COMBAT, tick = 5L)

        assertEquals(mapOf(ActionCategory.COMBAT to 1), tracker.snapshotFor(agent))
    }

    @Test
    fun `record increments on repeat and stamps the latest tick`() {
        tracker.record(agent, ActionCategory.GATHER, tick = 1L)
        tracker.record(agent, ActionCategory.GATHER, tick = 7L)
        tracker.record(agent, ActionCategory.GATHER, tick = 9L)

        assertEquals(mapOf(ActionCategory.GATHER to 3), tracker.snapshotFor(agent))
        val storedTick = dsl.select(AGENT_ACTION_COUNTERS.LAST_INCREMENTED_AT_TICK)
            .from(AGENT_ACTION_COUNTERS)
            .where(AGENT_ACTION_COUNTERS.AGENT_ID.eq(agent.id))
            .and(AGENT_ACTION_COUNTERS.CATEGORY.eq(ActionCategory.GATHER.name))
            .fetchOne(AGENT_ACTION_COUNTERS.LAST_INCREMENTED_AT_TICK)
        assertEquals(9L, storedTick)
    }

    @Test
    fun `snapshot exposes every category the agent has touched`() {
        tracker.record(agent, ActionCategory.COMBAT, tick = 1L)
        tracker.record(agent, ActionCategory.COMBAT, tick = 2L)
        tracker.record(agent, ActionCategory.CRAFT, tick = 3L)
        tracker.record(agent, ActionCategory.EXPLORE, tick = 4L)

        assertEquals(
            mapOf(
                ActionCategory.COMBAT to 2,
                ActionCategory.CRAFT to 1,
                ActionCategory.EXPLORE to 1,
            ),
            tracker.snapshotFor(agent),
        )
    }

    @Test
    fun `snapshot is empty for an agent with no recorded activity`() {
        assertEquals(emptyMap(), tracker.snapshotFor(agent))
    }

    @Test
    fun `agents do not see each other's counters`() {
        tracker.record(agent, ActionCategory.COMBAT, tick = 1L)
        tracker.record(other, ActionCategory.GATHER, tick = 1L)
        tracker.record(other, ActionCategory.GATHER, tick = 2L)

        assertEquals(mapOf(ActionCategory.COMBAT to 1), tracker.snapshotFor(agent))
        assertEquals(mapOf(ActionCategory.GATHER to 2), tracker.snapshotFor(other))
    }

    @Test
    fun `snapshot drops rows whose category column is unparseable`() {
        dsl.insertInto(AGENT_ACTION_COUNTERS)
            .set(AGENT_ACTION_COUNTERS.AGENT_ID, agent.id)
            .set(AGENT_ACTION_COUNTERS.CATEGORY, "QUANTUM_FORAGING")
            .set(AGENT_ACTION_COUNTERS.ACTION_COUNT, 7)
            .set(AGENT_ACTION_COUNTERS.LAST_INCREMENTED_AT_TICK, 1L)
            .execute()
        tracker.record(agent, ActionCategory.COMBAT, tick = 1L)

        assertEquals(mapOf(ActionCategory.COMBAT to 1), tracker.snapshotFor(agent))
    }
}
