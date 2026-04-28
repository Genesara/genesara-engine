package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
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
import kotlin.test.assertTrue

/**
 * Integration test for the inventory load/save path on [JooqWorldStateRepository]. Verifies
 * the round-trip alongside the existing positions/bodies persistence: inventory rows are
 * loaded into [WorldState.inventories], saved via the repository, and stay consistent across
 * a load → mutate → save → load cycle.
 *
 * The orphan-removal branch (item id no longer in the agent's inventory map) is exercised
 * explicitly because it's easy to regress when refactoring the upsert.
 */
@Testcontainers
class JooqWorldStateRepositoryInventoryIntegrationTest {

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
        dsl.truncate(AGENT_INVENTORY).cascade().execute()
        dsl.truncate(AGENT_BODIES).cascade().execute()
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        // Static config is reloaded against the empty regions/nodes tables; inventory paths
        // don't need a populated graph.
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        repository = JooqWorldStateRepository(dsl, staticConfig)
        repository.init()
    }

    @Test
    fun `save persists inventory rows that load round-trips into WorldState`() {
        val agent = AgentId(UUID.randomUUID())
        val state = WorldState.EMPTY.copy(
            inventories = mapOf(
                agent to AgentInventory(mapOf(ItemId("WOOD") to 7, ItemId("STONE") to 3)),
            ),
        )

        repository.save(state)
        val reloaded = repository.load()

        assertEquals(7, reloaded.inventories[agent]?.quantityOf(ItemId("WOOD")))
        assertEquals(3, reloaded.inventories[agent]?.quantityOf(ItemId("STONE")))
    }

    @Test
    fun `save removes rows for items dropped from the agent's inventory map`() {
        val agent = AgentId(UUID.randomUUID())
        repository.save(
            WorldState.EMPTY.copy(
                inventories = mapOf(
                    agent to AgentInventory(mapOf(ItemId("WOOD") to 7, ItemId("STONE") to 3)),
                ),
            )
        )

        // Now persist a state where STONE is gone — it must disappear from the table.
        repository.save(
            WorldState.EMPTY.copy(
                inventories = mapOf(agent to AgentInventory(mapOf(ItemId("WOOD") to 7))),
            )
        )

        val reloaded = repository.load().inventories[agent]
        assertEquals(7, reloaded?.quantityOf(ItemId("WOOD")))
        assertEquals(0, reloaded?.quantityOf(ItemId("STONE")))
    }

    @Test
    fun `save with an empty inventory clears the agent's rows entirely`() {
        val agent = AgentId(UUID.randomUUID())
        repository.save(
            WorldState.EMPTY.copy(
                inventories = mapOf(agent to AgentInventory(mapOf(ItemId("WOOD") to 1))),
            )
        )

        repository.save(WorldState.EMPTY.copy(inventories = mapOf(agent to AgentInventory.EMPTY)))

        val reloaded = repository.load().inventories
        assertTrue(agent !in reloaded || reloaded[agent]?.stacks.isNullOrEmpty())
    }
}
