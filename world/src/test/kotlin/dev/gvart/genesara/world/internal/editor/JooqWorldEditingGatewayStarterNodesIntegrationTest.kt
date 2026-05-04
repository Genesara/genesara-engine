package dev.gvart.genesara.world.internal.editor

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldEditingError
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_ADJACENCY
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGION_NEIGHBORS
import dev.gvart.genesara.world.internal.jooq.tables.references.STARTER_NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.mesh.GoldbergMeshGenerator
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.resources.ResourceSpawner
import dev.gvart.genesara.world.internal.starter.JooqStarterNodeLookup
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the new starter-nodes admin gateway methods. Exercises the
 * three verbs (list / upsert / remove) and the validation contract: race must exist,
 * node must belong to the world, and the node's terrain must be traversable.
 */
@Testcontainers
class JooqWorldEditingGatewayStarterNodesIntegrationTest {

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

    private val human = Race(
        id = RaceId("HUMAN_NORTHERN"),
        displayName = "Human (Northern)",
        weight = 1,
        attributeMods = AttributeMods(0, 0, 0, 0, 0, 0),
        description = "stub",
    )
    private val unknownRace = RaceId("ZORG_HIVE_TOTALLY_NOT_REAL")

    private lateinit var gateway: JooqWorldEditingGateway
    private lateinit var lookup: JooqStarterNodeLookup
    private var worldA: WorldId = WorldId(0L)
    private var worldB: WorldId = WorldId(0L)
    private var landNodeA: Long = 0L
    private var oceanNodeA: Long = 0L
    private var landNodeB: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.truncate(STARTER_NODES).cascade().execute()
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
            resourceSpawner = ResourceSpawner(NoTerrainSpawnsBalance),
            resourceStore = NoOpResourceStore,
            tickClock = ZeroClock,
            races = SingleRaceLookup(human),
            balance = OceanIsImpassable,
        )
        lookup = JooqStarterNodeLookup(dsl)

        // Seed two minimal worlds with a few hand-rolled nodes each. We don't go through
        // createWorld here because we only need a couple of well-typed terrains; the
        // gateway's region/world joins will discover the rest.
        worldA = WorldId(insertWorld("world-A"))
        worldB = WorldId(insertWorld("world-B"))
        val regionA = insertRegion(worldA.value, sphereIndex = 0)
        val regionB = insertRegion(worldB.value, sphereIndex = 0)
        landNodeA = insertNode(regionA, q = 0, r = 0, terrain = Terrain.PLAINS)
        oceanNodeA = insertNode(regionA, q = 1, r = 0, terrain = Terrain.OCEAN)
        landNodeB = insertNode(regionB, q = 0, r = 0, terrain = Terrain.PLAINS)
    }

    @Test
    fun `upsertStarterNode persists a row that the lookup can read back`() {
        gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeA))

        assertEquals(NodeId(landNodeA), lookup.byRace(human.id))
    }

    @Test
    fun `upsertStarterNode is idempotent and overwrites the existing mapping`() {
        gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeA))
        gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeA))

        // Single row regardless of how many times we upsert; PK is race_id.
        val rows = dsl.selectCount().from(STARTER_NODES).fetchOne(0, Int::class.javaObjectType)
        assertEquals(1, rows)
    }

    @Test
    fun `upsertStarterNode rejects a node that doesn't belong to the requested world`() {
        // landNodeB lives in worldB; pointing worldA at it should be NodeNotInWorld.
        val err = assertThrows<WorldEditingError.NodeNotInWorld> {
            gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeB))
        }
        assertEquals(worldA, err.worldId)
        assertEquals(NodeId(landNodeB), err.nodeId)
    }

    @Test
    fun `upsertStarterNode rejects an unknown race`() {
        assertThrows<WorldEditingError.UnknownRace> {
            gateway.upsertStarterNode(worldA, unknownRace, NodeId(landNodeA))
        }
    }

    @Test
    fun `upsertStarterNode rejects a non-traversable terrain so agents do not spawn stuck`() {
        val err = assertThrows<WorldEditingError.StarterNodeNotTraversable> {
            gateway.upsertStarterNode(worldA, human.id, NodeId(oceanNodeA))
        }
        assertEquals(Terrain.OCEAN, err.terrain)
    }

    @Test
    fun `upsertStarterNode rejects an unknown world`() {
        val ghost = WorldId(99_999L)
        assertThrows<WorldEditingError.WorldNotFound> {
            gateway.upsertStarterNode(ghost, human.id, NodeId(landNodeA))
        }
    }

    @Test
    fun `listStarterNodes returns assignments scoped to the requested world only`() {
        // Seed worldB with its own row by inserting directly (the gateway's upsert
        // would overwrite the global PK). Then assert listing scopes to each world.
        dsl.insertInto(STARTER_NODES)
            .set(STARTER_NODES.RACE_ID, human.id.value)
            .set(STARTER_NODES.NODE_ID, landNodeB)
            .execute()

        val a = gateway.listStarterNodes(worldA)
        val b = gateway.listStarterNodes(worldB)

        // The single global row points at landNodeB → only worldB sees it; worldA's
        // listing is empty even though the table has a row.
        assertTrue(a.isEmpty())
        assertEquals(1, b.size)
        assertEquals(NodeId(landNodeB), b.single().nodeId)
    }

    @Test
    fun `removeStarterNode deletes the mapping and returns true`() {
        gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeA))

        val removed = gateway.removeStarterNode(worldA, human.id)

        assertTrue(removed)
        assertNull(lookup.byRace(human.id))
    }

    @Test
    fun `removeStarterNode returns false when no mapping existed`() {
        assertEquals(false, gateway.removeStarterNode(worldA, human.id))
    }

    @Test
    fun `upserting the same race in worldB silently overwrites worldA's mapping`() {
        // Documented v1 limitation: starter_nodes PK is race_id (no world_id), so the
        // table is global. This test pins the contract — when (if?) the schema gains a
        // world_id column, this test must flip from "B wins" to "both coexist" and that
        // schema change should be loud.
        gateway.upsertStarterNode(worldA, human.id, NodeId(landNodeA))
        gateway.upsertStarterNode(worldB, human.id, NodeId(landNodeB))

        assertTrue(gateway.listStarterNodes(worldA).isEmpty(), "worldA should no longer see this race")
        assertEquals(NodeId(landNodeB), gateway.listStarterNodes(worldB).single().nodeId)
        assertEquals(NodeId(landNodeB), lookup.byRace(human.id))
    }


    private fun insertWorld(name: String): Long =
        dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

    private fun insertRegion(worldId: Long, sphereIndex: Int): Long =
        dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, sphereIndex)
            .set(REGIONS.BIOME, Biome.PLAINS.name)
            .set(REGIONS.CLIMATE, Climate.CONTINENTAL.name)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, org.jooq.JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!

    private fun insertNode(regionId: Long, q: Int, r: Int, terrain: Terrain): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, q)
            .set(NODES.R, r)
            .set(NODES.TERRAIN, terrain.name)
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!

    private class SingleRaceLookup(private val race: Race) : RaceLookup {
        override fun byId(id: RaceId): Race? = if (id == race.id) race else null
        override fun all(): List<Race> = listOf(race)
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

    private object NoTerrainSpawnsBalance : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
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
}
