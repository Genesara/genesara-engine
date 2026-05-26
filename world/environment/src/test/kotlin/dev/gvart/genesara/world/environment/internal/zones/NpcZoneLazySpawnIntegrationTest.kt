package dev.gvart.genesara.world.environment.internal.zones

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawn
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.balance.BiomeProperties
import dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NPC_ZONES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

@Testcontainers
class NpcZoneLazySpawnIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("npc_zones_lazy_it")
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

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val agent = AgentId(UUID.randomUUID())

    private val wolfDef = npcDef("GRAY_WOLF", weight = 1)
    private val boarDef = npcDef("WILD_BOAR", weight = 1)

    private lateinit var store: JooqNpcZonesStore
    private lateinit var lookup: NpcZoneLookupImpl
    private lateinit var spawn: LazyNpcSpawn
    private var worldId: Long = 0L
    private var regionId: Long = 0L
    private var nodeId: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.deleteFrom(NPC_ZONES).execute()
        dsl.deleteFrom(NODES).execute()
        dsl.deleteFrom(REGIONS).execute()
        dsl.deleteFrom(WORLDS).execute()
        worldId = insertWorld()
        regionId = insertRegion(worldId)
        nodeId = insertNode(regionId)

        store = JooqNpcZonesStore(dsl, mapper)
        lookup = NpcZoneLookupImpl(store)
        spawn = LazyNpcSpawn(
            catalog = catalogFor(wolfDef, boarDef),
            balance = balance(respawnTicks = 500L),
            worldDef = forestCapacity(2),
            clearedStore = stubClearedStore(),
            zoneLookup = lookup,
        )
    }

    @Test
    fun `wolves-only node zone seeds only wolves even when biome catalog allows boar`() {
        store.insert(
            zone(
                scope = NpcZoneScope.NODE,
                nodeId = NodeId(nodeId),
                weights = mapOf("GRAY_WOLF" to 1),
                max = 3,
            )
        )

        val (after, _) = spawn.maybeSeed(baseState(), NodeId(nodeId), agent, tick = 1000L, rng = Random(0L))

        assertEquals(3, after.npcs.size)
        assertTrue(after.npcs.values.all { it.type == wolfDef.type })
    }

    @Test
    fun `no zone falls through to biome behaviour and seeds biome catalog mix`() {
        val (after, _) = spawn.maybeSeed(baseState(), NodeId(nodeId), agent, tick = 1000L, rng = Random(0L))

        assertEquals(2, after.npcs.size)
        assertTrue(after.npcs.values.all { it.type == wolfDef.type || it.type == boarDef.type })
    }

    @Test
    fun `inactive zone is ignored and falls through to biome behaviour`() {
        store.insert(
            zone(scope = NpcZoneScope.NODE, nodeId = NodeId(nodeId), weights = mapOf("GRAY_WOLF" to 1), max = 3)
                .copy(active = false)
        )

        val (after, _) = spawn.maybeSeed(baseState(), NodeId(nodeId), agent, tick = 1000L, rng = Random(0L))

        assertEquals(2, after.npcs.size)
    }

    @Test
    fun `node zone resolution beats region zone`() {
        store.insert(
            zone(
                scope = NpcZoneScope.REGION,
                regionId = RegionId(regionId),
                weights = mapOf("WILD_BOAR" to 1),
                max = 5,
            )
        )
        store.insert(
            zone(scope = NpcZoneScope.NODE, nodeId = NodeId(nodeId), weights = mapOf("GRAY_WOLF" to 1), max = 1)
        )

        val (after, _) = spawn.maybeSeed(baseState(), NodeId(nodeId), agent, tick = 1000L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
        assertEquals(wolfDef.type, after.npcs.values.single().type)
    }

    private fun baseState() = WorldState(
        regions = mapOf(
            RegionId(regionId) to Region(
                id = RegionId(regionId), worldId = WorldId(worldId), sphereIndex = 0,
                biome = Biome.FOREST, climate = Climate.OCEANIC,
                centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
            )
        ),
        nodes = mapOf(NodeId(nodeId) to Node(NodeId(nodeId), RegionId(regionId), 0, 0, Terrain.FOREST, emptySet())),
        positions = emptyMap(),
        bodies = emptyMap(),
        inventories = emptyMap(),
    )

    private fun zone(
        scope: NpcZoneScope,
        nodeId: NodeId? = null,
        regionId: RegionId? = null,
        weights: Map<String, Int>,
        max: Int,
        respawnTicks: Int? = null,
    ) = NpcZone(
        zoneId = UUID.randomUUID(),
        worldId = WorldId(worldId),
        scope = scope,
        regionId = regionId,
        nodeId = nodeId,
        weights = weights.mapKeys { NpcType(it.key) },
        maxConcurrent = max,
        respawnTicks = respawnTicks,
        active = true,
        createdBy = UUID.randomUUID(),
        createdAtTick = 0L,
    )

    private fun npcDef(type: String, weight: Int) = NpcDef(
        type = NpcType(type),
        displayName = type,
        hpMax = 30,
        damage = 1,
        damageType = DamageType.BLUNT,
        range = 1,
        attackIntervalTicks = 4,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = weight,
    )

    private fun catalogFor(vararg defs: NpcDef): NpcCatalog {
        val byType = defs.associateBy { it.type }
        return object : NpcCatalog {
            override fun byType(type: NpcType): NpcDef? = byType[type]
            override fun all(): Collection<NpcDef> = byType.values
            override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
        }
    }

    private fun forestCapacity(capacity: Int) = WorldDefinitionProperties(
        biomes = mapOf(
            Biome.FOREST to BiomeProperties(
                displayName = "Forest",
                nodeNpcCapacity = capacity,
                nodeSpawnProbability = 1.0,
            ),
        ),
    )

    private fun stubClearedStore() = object : NodeClearedTimestampStore {
        override fun lastClearedTick(nodeId: NodeId): Long = 0L
        override fun setLastClearedTick(nodeId: NodeId, tick: Long) = Unit
    }

    private fun balance(respawnTicks: Long) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 0
        override fun drinkThirstRefill(): Int = 0
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun npcRespawnTicks(): Long = respawnTicks
    }

    private fun insertWorld(): Long =
        dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "lazy-zone-it-${System.nanoTime()}")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

    private fun insertRegion(worldId: Long): Long =
        dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!

    private fun insertNode(regionId: Long): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "FOREST")
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
}
