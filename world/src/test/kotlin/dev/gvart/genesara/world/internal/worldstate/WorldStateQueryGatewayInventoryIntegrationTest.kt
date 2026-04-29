package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
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

/**
 * Integration test for [WorldStateQueryGateway.inventoryOf]. Exercises the live SELECT
 * against `agent_inventory`, including the deterministic ordering by item id (alphabetical)
 * that callers rely on for stable rendering.
 */
@Testcontainers
class WorldStateQueryGatewayInventoryIntegrationTest {

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

    private lateinit var gateway: WorldStateQueryGateway

    @BeforeEach
    fun resetInventory() {
        dsl.truncate(AGENT_INVENTORY).cascade().execute()
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        staticConfig.reload()
        gateway = WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes(),
            resources = EmptyResourceStore,
        )
    }

    @Test
    fun `inventoryOf returns entries ordered by item id`() {
        val agent = AgentId(UUID.randomUUID())
        // Insert in a non-alphabetical order to exercise the ORDER BY clause.
        listOf("STONE" to 3, "BERRY" to 12, "WOOD" to 7).forEach { (item, qty) ->
            dsl.insertInto(AGENT_INVENTORY)
                .set(AGENT_INVENTORY.AGENT_ID, agent.id)
                .set(AGENT_INVENTORY.ITEM_ID, item)
                .set(AGENT_INVENTORY.QUANTITY, qty)
                .execute()
        }

        val view = gateway.inventoryOf(agent)

        assertEquals(
            listOf(
                InventoryEntry(ItemId("BERRY"), 12),
                InventoryEntry(ItemId("STONE"), 3),
                InventoryEntry(ItemId("WOOD"), 7),
            ),
            view.entries,
        )
    }

    @Test
    fun `inventoryOf returns an empty list when the agent has no rows`() {
        val ghost = AgentId(UUID.randomUUID())
        assertEquals(emptyList(), gateway.inventoryOf(ghost).entries)
    }

    @Test
    fun `inventoryOf does not bleed entries across agents`() {
        val a = AgentId(UUID.randomUUID())
        val b = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENT_INVENTORY)
            .set(AGENT_INVENTORY.AGENT_ID, a.id)
            .set(AGENT_INVENTORY.ITEM_ID, "WOOD")
            .set(AGENT_INVENTORY.QUANTITY, 5)
            .execute()
        dsl.insertInto(AGENT_INVENTORY)
            .set(AGENT_INVENTORY.AGENT_ID, b.id)
            .set(AGENT_INVENTORY.ITEM_ID, "STONE")
            .set(AGENT_INVENTORY.QUANTITY, 9)
            .execute()

        assertEquals(listOf(InventoryEntry(ItemId("WOOD"), 5)), gateway.inventoryOf(a).entries)
        assertEquals(listOf(InventoryEntry(ItemId("STONE"), 9)), gateway.inventoryOf(b).entries)
    }

    private class NoOpStarterNodes : StarterNodeLookup {
        override fun byRace(race: RaceId): NodeId? = null
    }

    private object EmptyResourceStore : dev.gvart.genesara.world.internal.resources.NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long) = null
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {}
        override fun seed(rows: Collection<dev.gvart.genesara.world.internal.resources.InitialResourceRow>, tick: Long) {}
    }
}
