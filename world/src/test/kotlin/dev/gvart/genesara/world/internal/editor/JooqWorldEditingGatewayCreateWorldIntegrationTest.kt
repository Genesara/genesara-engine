package dev.gvart.genesara.world.internal.editor

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGION_NEIGHBORS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.mesh.GoldbergMeshGenerator
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.resources.ResourceSpawner
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.internal.balance.BalanceLookup
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check for the Phase-0 finishing slice: `createWorld` paints a biome and
 * climate onto every region, marks ~30% of regions as OCEAN with contiguity bias, and
 * the `nodes` table picks up `pvp_enabled = true` by default.
 *
 * Worlds are persisted across the whole class but each test asserts on its own world to
 * avoid cross-test contamination. The unit-level mechanics live in [BiomeAssignerTest];
 * this test only verifies the wiring against a real Postgres + jOOQ stack.
 */
@Testcontainers
class JooqWorldEditingGatewayCreateWorldIntegrationTest {

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

    private lateinit var gateway: JooqWorldEditingGateway

    @BeforeEach
    fun reset() {
        dsl.truncate(REGION_NEIGHBORS).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()
        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val staticConfig = WorldStaticConfig(dsl, mapper)
        staticConfig.reload()
        gateway = JooqWorldEditingGateway(
            dsl = dsl,
            mesh = GoldbergMeshGenerator(),
            hexes = HexGridGenerator(),
            biomeAssigner = BiomeAssigner(),
            staticConfig = staticConfig,
            mapper = mapper,
            // Stubbed resource side: createWorld doesn't seed hexes (and therefore doesn't
            // touch the resource store) — but the gateway constructor still needs them.
            resourceSpawner = ResourceSpawner(NoTerrainSpawnsBalance),
            resourceStore = NoOpResourceStore,
            tickClock = ZeroClock,
            races = StubRaceLookup,
            balance = NoTerrainSpawnsBalance,
        )
    }

    @Test
    fun `createWorld paints a biome and climate onto every region`() {
        val world = gateway.createWorld(name = "biome-paint", requestedNodeCount = 92, nodeSize = 1)

        val regions = dsl.select(REGIONS.ID, REGIONS.BIOME, REGIONS.CLIMATE)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(world.id.value))
            .fetch()

        assertTrue(regions.isNotEmpty())
        for (row in regions) {
            assertNotNull(row[REGIONS.BIOME], "region ${row[REGIONS.ID]} biome left null")
            assertNotNull(row[REGIONS.CLIMATE], "region ${row[REGIONS.ID]} climate left null")
        }
    }

    @Test
    fun `createWorld produces a non-trivial mix of OCEAN and land regions`() {
        val world = gateway.createWorld(name = "ocean-fringe", requestedNodeCount = 92, nodeSize = 1)

        val biomes = dsl.select(REGIONS.BIOME)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(world.id.value))
            .fetch { Biome.valueOf(it[REGIONS.BIOME]!!) }

        val oceanCount = biomes.count { it == Biome.OCEAN }
        // Default fraction is 30% with flood-fill slack — guard against regressions
        // that drop the count to a token sliver. 10% is the absolute floor; if the
        // assigner ever produces less than that the world is no longer "fringe-y."
        assertTrue(oceanCount >= biomes.size / 10, "expected non-trivial ocean coverage, got $oceanCount / ${biomes.size}")
        assertTrue(oceanCount < biomes.size, "every region became OCEAN — land cannot be empty")
    }

    @Test
    fun `nodes inserted via the editor default to pvp_enabled = true`() {
        // Tier-0 sanity: schema-level default of TRUE survives an insert that doesn't
        // touch the column. Once Phase 2 / 3 land we'll have explicit FALSE writes for
        // green zones; this guards the scaffolding the rest of the engine builds on.
        val world = gateway.createWorld(name = "pvp-default", requestedNodeCount = 92, nodeSize = 1)
        val regionId = dsl.select(REGIONS.ID)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(world.id.value))
            .limit(1)
            .fetchOne()!!
            .value1()!!

        // Insert a node directly without specifying pvp_enabled — Postgres should fill
        // in TRUE per the V7 migration's column default.
        val nodeId = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, Terrain.PLAINS.name)
            .returningResult(NODES.ID, NODES.PVP_ENABLED)
            .fetchOne()!!
        assertEquals(true, nodeId[NODES.PVP_ENABLED])
    }

    @Test
    fun `randomSpawnableNode never returns an OCEAN tile`() {
        // Seed a world with a mix of land and ocean nodes — randomSpawnableNode must
        // filter out non-traversable terrain. Without this guard ~30% of fresh agents
        // would spawn on OCEAN and be unable to move.
        val world = gateway.createWorld(name = "spawn-traversable", requestedNodeCount = 12, nodeSize = 1)
        val regionId = dsl.select(REGIONS.ID)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(world.id.value))
            .limit(1)
            .fetchOne()!!
            .value1()!!
        // Insert 5 OCEAN nodes and 5 PLAINS nodes directly. randomSpawnableNode picks
        // from the in-memory static config so we don't need to go through seedHexes.
        val plainsIds = (0 until 5).map { i ->
            dsl.insertInto(NODES)
                .set(NODES.REGION_ID, regionId)
                .set(NODES.Q, i)
                .set(NODES.R, 0)
                .set(NODES.TERRAIN, Terrain.PLAINS.name)
                .returningResult(NODES.ID)
                .fetchOne()!!
                .value1()!!
        }.toSet()
        repeat(5) { i ->
            dsl.insertInto(NODES)
                .set(NODES.REGION_ID, regionId)
                .set(NODES.Q, i)
                .set(NODES.R, 1)
                .set(NODES.TERRAIN, Terrain.OCEAN.name)
                .execute()
        }

        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val staticConfig = WorldStaticConfig(dsl, mapper)
        staticConfig.reload()
        val queryGateway = dev.gvart.genesara.world.internal.worldstate.WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes,
            resources = NoOpResourceStore,
            balance = OceanIsImpassable,
            groundItems = dev.gvart.genesara.world.internal.testsupport.NoOpGroundItemStore,
        )

        // 50 samples — chance of picking only PLAINS by luck if the filter were broken
        // is (5/10)^50 ≈ 9e-16, statistically certain to catch a regression.
        repeat(50) {
            val picked = queryGateway.randomSpawnableNode()
            assertNotNull(picked)
            assertTrue(picked.value in plainsIds, "randomSpawnableNode picked ocean tile $picked")
        }
    }

    @Test
    fun `pvp_enabled=false round-trips through WorldStaticConfig into the domain Node`() {
        // Direct column write → reload → domain object must reflect the false. Locks
        // the read path that the look_around payload (and future PvP-zone enforcement)
        // depends on.
        val world = gateway.createWorld(name = "pvp-roundtrip", requestedNodeCount = 92, nodeSize = 1)
        val regionId = dsl.select(REGIONS.ID)
            .from(REGIONS)
            .where(REGIONS.WORLD_ID.eq(world.id.value))
            .limit(1)
            .fetchOne()!!
            .value1()!!
        val nodeId = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, Terrain.PLAINS.name)
            .set(NODES.PVP_ENABLED, false)
            .returningResult(NODES.ID)
            .fetchOne()!!
            .value1()!!

        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val staticConfig = WorldStaticConfig(dsl, mapper)
        staticConfig.reload()
        val loaded = staticConfig.node(NodeId(nodeId))
        assertNotNull(loaded)
        assertEquals(false, loaded.pvpEnabled)
    }

    private object NoOpResourceStore : NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? = null
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) = Unit
        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) = Unit
    }

    private object ZeroClock : TickClock {
        override fun currentTick(): Long = 0L
    }

    private object NoOpStarterNodes : dev.gvart.genesara.world.StarterNodeLookup {
        override fun byRace(race: dev.gvart.genesara.player.RaceId): NodeId? = null
    }

    private object NoTerrainSpawnsBalance : BalanceLookup {
        override fun moveStaminaCost(
            biome: dev.gvart.genesara.world.Biome,
            climate: dev.gvart.genesara.world.Climate,
            terrain: Terrain,
        ): Int = 1
        override fun staminaRegenPerTick(climate: dev.gvart.genesara.world.Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    private object OceanIsImpassable : BalanceLookup by NoTerrainSpawnsBalance {
        override fun isTraversable(terrain: Terrain): Boolean = terrain != Terrain.OCEAN
    }

    private object StubRaceLookup : dev.gvart.genesara.player.RaceLookup {
        // Single race id used by the starter-node tests; createWorld itself doesn't read
        // the race catalog so this is only meaningful for callers that exercise
        // upsertStarterNode further down.
        private val human = dev.gvart.genesara.player.Race(
            id = dev.gvart.genesara.player.RaceId("HUMAN_NORTHERN"),
            displayName = "Human (Northern)",
            weight = 1,
            attributeMods = dev.gvart.genesara.player.AttributeMods(0, 0, 0, 0, 0, 0),
            description = "stub",
        )
        override fun byId(id: dev.gvart.genesara.player.RaceId): dev.gvart.genesara.player.Race? =
            if (id == human.id) human else null
        override fun all(): List<dev.gvart.genesara.player.Race> = listOf(human)
    }
}
