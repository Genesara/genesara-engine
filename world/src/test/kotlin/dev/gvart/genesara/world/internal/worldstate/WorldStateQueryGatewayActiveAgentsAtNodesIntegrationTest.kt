package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.JSON
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

@Testcontainers
class WorldStateQueryGatewayActiveAgentsAtNodesIntegrationTest {

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
    fun resetState() {
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()

        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        staticConfig.reload()
        gateway = WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes(),
            resources = EmptyResourceStore,
            balance = AlwaysTraversableBalance,
            groundItems = dev.gvart.genesara.world.internal.testsupport.NoOpGroundItemStore,
            worldRouter = dev.gvart.genesara.world.internal.testsupport.NoOpAgentWorldRouter(),
            tickCounter = dev.gvart.genesara.world.internal.testsupport.FixedWorldTickCounter(),
        )
    }

    @Test
    fun `activeAgentsAtNodes groups active agents by node and filters out inactive rows`() {
        val worldId = seedWorld()
        val regionId = seedRegion(worldId, sphereIndex = 0)
        val nodeA = seedNode(regionId, q = 0, r = 0)
        val nodeB = seedNode(regionId, q = 1, r = 0)
        val nodeUnqueried = seedNode(regionId, q = 2, r = 0)

        val activeOnA1 = AgentId(UUID.randomUUID())
        val activeOnA2 = AgentId(UUID.randomUUID())
        val activeOnB = AgentId(UUID.randomUUID())
        val inactiveOnA = AgentId(UUID.randomUUID())
        val activeButNotQueried = AgentId(UUID.randomUUID())

        insertPosition(activeOnA1, nodeA, worldId, active = true)
        insertPosition(activeOnA2, nodeA, worldId, active = true)
        insertPosition(activeOnB, nodeB, worldId, active = true)
        insertPosition(inactiveOnA, nodeA, worldId, active = false)
        insertPosition(activeButNotQueried, nodeUnqueried, worldId, active = true)

        val grouped = gateway.activeAgentsAtNodes(setOf(NodeId(nodeA), NodeId(nodeB)))

        assertEquals(setOf(activeOnA1, activeOnA2), grouped[NodeId(nodeA)]?.toSet())
        assertEquals(listOf(activeOnB), grouped[NodeId(nodeB)])
        assertTrue(grouped.values.flatten().none { it == inactiveOnA }, "inactive row must be filtered")
        assertTrue(grouped.values.flatten().none { it == activeButNotQueried }, "node not in query must be absent")
    }

    @Test
    fun `activeAgentsAtNodes returns an empty map for empty input without touching the DB`() {
        assertEquals(emptyMap(), gateway.activeAgentsAtNodes(emptySet()))
    }

    @Test
    fun `activeAgentsAtNodes omits nodes with no active occupants`() {
        val worldId = seedWorld()
        val regionId = seedRegion(worldId, sphereIndex = 0)
        val populated = seedNode(regionId, q = 0, r = 0)
        val empty = seedNode(regionId, q = 1, r = 0)

        val agent = AgentId(UUID.randomUUID())
        insertPosition(agent, populated, worldId, active = true)

        val grouped = gateway.activeAgentsAtNodes(setOf(NodeId(populated), NodeId(empty)))

        assertEquals(listOf(agent), grouped[NodeId(populated)])
        assertTrue(NodeId(empty) !in grouped, "empty node must not appear in the result map")
    }

    private fun seedWorld(): Long = dsl.insertInto(WORLDS)
        .set(WORLDS.NAME, "world-${UUID.randomUUID()}")
        .set(WORLDS.NODE_COUNT, 1)
        .set(WORLDS.NODE_SIZE, 1)
        .set(WORLDS.FREQUENCY, 1)
        .returningResult(WORLDS.ID)
        .fetchOne()!!.value1()!!

    private fun seedRegion(worldId: Long, sphereIndex: Int): Long = dsl.insertInto(REGIONS)
        .set(REGIONS.WORLD_ID, worldId)
        .set(REGIONS.SPHERE_INDEX, sphereIndex)
        .set(REGIONS.BIOME, "PLAINS")
        .set(REGIONS.CLIMATE, "OCEANIC")
        .set(REGIONS.CENTROID_X, 0.0)
        .set(REGIONS.CENTROID_Y, 0.0)
        .set(REGIONS.CENTROID_Z, 1.0)
        .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
        .returningResult(REGIONS.ID)
        .fetchOne()!!.value1()!!

    private fun seedNode(regionId: Long, q: Int, r: Int): Long = dsl.insertInto(NODES)
        .set(NODES.REGION_ID, regionId)
        .set(NODES.Q, q)
        .set(NODES.R, r)
        .set(NODES.TERRAIN, "PLAINS")
        .returningResult(NODES.ID)
        .fetchOne()!!.value1()!!

    private fun insertPosition(agent: AgentId, nodeId: Long, worldId: Long, active: Boolean) {
        dsl.insertInto(AGENT_POSITIONS)
            .set(AGENT_POSITIONS.AGENT_ID, agent.id)
            .set(AGENT_POSITIONS.NODE_ID, nodeId)
            .set(AGENT_POSITIONS.WORLD_ID, worldId)
            .set(AGENT_POSITIONS.ACTIVE, active)
            .execute()
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
