package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KILL_STREAKS
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
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
import kotlin.test.assertNull

/**
 * Integration test for kill-streak load/save on [JooqWorldStateRepository]. Verifies
 * the round-trip: a non-empty `AgentKillStreak` saved via the repository reloads via
 * `WorldState.killStreaks`, and a streak reset to `EMPTY` deletes the row rather than
 * persisting a (0, 0) sentinel.
 */
@Testcontainers
class JooqAgentKillStreakIntegrationTest {

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

    private lateinit var repository: JooqWorldStateRepository

    @BeforeEach
    fun resetState() {
        dsl.truncate(AGENT_KILL_STREAKS).cascade().execute()
        dsl.truncate(AGENT_INVENTORY).cascade().execute()
        dsl.truncate(AGENT_BODIES).cascade().execute()
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        repository = JooqWorldStateRepository(dsl, staticConfig)
        repository.init()
    }

    @Test
    fun `non-empty streak round-trips through save and load`() {
        val agent = AgentId(UUID.randomUUID())
        val state = WorldState.EMPTY.copy(
            killStreaks = mapOf(agent to AgentKillStreak(killCount = 7, windowStartTick = 100L)),
        )

        repository.save(state)
        val reloaded = repository.load()

        assertEquals(
            AgentKillStreak(killCount = 7, windowStartTick = 100L),
            reloaded.killStreaks[agent],
        )
    }

    @Test
    fun `EMPTY streak deletes the row instead of persisting zero sentinels`() {
        val agent = AgentId(UUID.randomUUID())
        repository.save(
            WorldState.EMPTY.copy(killStreaks = mapOf(agent to AgentKillStreak(5, windowStartTick = 50L))),
        )

        repository.save(WorldState.EMPTY.copy(killStreaks = mapOf(agent to AgentKillStreak.EMPTY)))

        val reloaded = repository.load().killStreaks
        assertNull(reloaded[agent], "EMPTY streak should clear the row")
    }

    @Test
    fun `existing streak update overwrites kill_count and window_start_tick`() {
        val agent = AgentId(UUID.randomUUID())
        repository.save(
            WorldState.EMPTY.copy(killStreaks = mapOf(agent to AgentKillStreak(3, windowStartTick = 10L))),
        )
        repository.save(
            WorldState.EMPTY.copy(killStreaks = mapOf(agent to AgentKillStreak(8, windowStartTick = 200L))),
        )

        val reloaded = repository.load().killStreaks[agent]
        assertEquals(AgentKillStreak(killCount = 8, windowStartTick = 200L), reloaded)
    }
}
