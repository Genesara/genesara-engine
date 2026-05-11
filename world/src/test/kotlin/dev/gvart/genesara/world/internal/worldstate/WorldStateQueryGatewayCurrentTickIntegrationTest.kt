package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.testsupport.FixedWorldTickCounter
import dev.gvart.genesara.world.internal.testsupport.NoOpAgentWorldRouter
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

@Testcontainers
class WorldStateQueryGatewayCurrentTickIntegrationTest {

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

    @Test
    fun `currentTickFor returns the per-world tick from the counter for the agent's routed world`() {
        val gateway = buildGateway(
            NoOpAgentWorldRouter(WorldId(7L)),
            FixedWorldTickCounter(tick = 4_242L),
        )

        assertEquals(4_242L, gateway.currentTickFor(AgentId(UUID.randomUUID())))
    }

    @Test
    fun `currentTickFor falls back to zero when no world is routable for the agent`() {
        val gateway = buildGateway(
            NoOpAgentWorldRouter(worldId = null),
            FixedWorldTickCounter(tick = 999L),
        )

        assertEquals(0L, gateway.currentTickFor(AgentId(UUID.randomUUID())))
    }

    private fun buildGateway(
        router: NoOpAgentWorldRouter,
        counter: FixedWorldTickCounter,
    ): WorldStateQueryGateway {
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        staticConfig.reload()
        return WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes,
            resources = EmptyResourceStore,
            balance = AlwaysTraversableBalance,
            groundItems = dev.gvart.genesara.world.internal.testsupport.NoOpGroundItemStore,
            worldRouter = router,
            tickCounter = counter,
        )
    }

    private object NoOpStarterNodes : StarterNodeLookup {
        override fun byRace(race: RaceId): NodeId? = null
    }

    private object EmptyResourceStore : dev.gvart.genesara.world.internal.resources.NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: dev.gvart.genesara.world.ItemId, tick: Long) = null
        override fun decrement(nodeId: NodeId, item: dev.gvart.genesara.world.ItemId, amount: Int, tick: Long) {}
        override fun seed(rows: Collection<dev.gvart.genesara.world.internal.resources.InitialResourceRow>, tick: Long) {}
    }

    private object AlwaysTraversableBalance : dev.gvart.genesara.world.internal.balance.BalanceLookup {
        override fun moveStaminaCost(
            biome: dev.gvart.genesara.world.Biome,
            climate: dev.gvart.genesara.world.Climate,
            terrain: dev.gvart.genesara.world.Terrain,
        ): Int = 1
        override fun staminaRegenPerTick(climate: dev.gvart.genesara.world.Climate): Int = 0
        override fun resourceSpawnsFor(terrain: dev.gvart.genesara.world.Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: dev.gvart.genesara.world.ItemId): Int = 5
        override fun harvestYield(item: dev.gvart.genesara.world.ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: dev.gvart.genesara.world.Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: dev.gvart.genesara.world.Terrain): Boolean = true
    }
}
