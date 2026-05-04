package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
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
 * Integration test for the new `bodyOf` query on [WorldStateQueryGateway].
 * Other gateway methods are covered by existing reducer / handler tests; this fills the
 * gap created by adding a public [BodyView] projection on top of the internal `AgentBody`.
 */
@Testcontainers
class WorldStateQueryGatewayBodyViewIntegrationTest {

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
    fun resetBodies() {
        dsl.truncate(AGENT_BODIES).cascade().execute()
        // bodyOf does not touch static config or starter nodes; construct an empty static
        // config and a no-op starter lookup just to satisfy the constructor.
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        staticConfig.reload()
        gateway = WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes(),
            resources = EmptyResourceStore,
            balance = AlwaysTraversableBalance,
        )
    }

    @Test
    fun `bodyOf projects every column from agent_bodies into a BodyView`() {
        // Reminder: when adding a column to `agent_bodies` (e.g. armor_class for a future combat
        // slice), update both the seed below AND `BodyView` + `WorldStateQueryGateway.bodyOf`,
        // otherwise `BodyView` will silently drop the new column and this test will keep passing.
        val agent = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, 80).set(AGENT_BODIES.MAX_HP, 100)
            .set(AGENT_BODIES.STAMINA, 25).set(AGENT_BODIES.MAX_STAMINA, 50)
            .set(AGENT_BODIES.MANA, 5).set(AGENT_BODIES.MAX_MANA, 15)
            .set(AGENT_BODIES.HUNGER, 90).set(AGENT_BODIES.MAX_HUNGER, 100)
            .set(AGENT_BODIES.THIRST, 70).set(AGENT_BODIES.MAX_THIRST, 100)
            .set(AGENT_BODIES.SLEEP, 40).set(AGENT_BODIES.MAX_SLEEP, 100)
            .execute()

        val body = gateway.bodyOf(agent)

        assertEquals(
            BodyView(
                hp = 80, maxHp = 100,
                stamina = 25, maxStamina = 50,
                mana = 5, maxMana = 15,
                hunger = 90, maxHunger = 100,
                thirst = 70, maxThirst = 100,
                sleep = 40, maxSleep = 100,
            ),
            body,
        )
    }

    @Test
    fun `bodyOf returns null when no row exists for the agent`() {
        val ghost = AgentId(UUID.randomUUID())
        assertNull(gateway.bodyOf(ghost))
    }

    private class NoOpStarterNodes : StarterNodeLookup {
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
