package dev.gvart.genesara.world.environment.internal.zones

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZoneAdminError
import dev.gvart.genesara.world.NpcZoneCreateSpec
import dev.gvart.genesara.world.NpcZonePatch
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NPC_ZONES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jooq.DSLContext
import org.jooq.JSON
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

@Testcontainers
class NpcZoneAdminGatewayImplIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("npc_zones_admin_it")
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
    private val wolfType = NpcType("GRAY_WOLF")
    private val wolfDef = NpcDef(
        type = wolfType,
        displayName = "Gray Wolf",
        hpMax = 30,
        damage = 1,
        damageType = DamageType.PIERCE,
        range = 1,
        attackIntervalTicks = 4,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
    )
    private val admin = UUID.randomUUID()

    private lateinit var store: JooqNpcZonesStore
    private lateinit var gateway: NpcZoneAdminGatewayImpl
    private var worldOneId: Long = 0L
    private var worldTwoId: Long = 0L
    private var regionOneId: Long = 0L
    private var regionTwoId: Long = 0L
    private var nodeInWorldOneId: Long = 0L
    private var nodeInWorldTwoId: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.deleteFrom(NPC_ZONES).execute()
        dsl.deleteFrom(NODES).execute()
        dsl.deleteFrom(REGIONS).execute()
        dsl.deleteFrom(WORLDS).execute()
        worldOneId = insertWorld("w1")
        worldTwoId = insertWorld("w2")
        regionOneId = insertRegion(worldOneId)
        regionTwoId = insertRegion(worldTwoId)
        nodeInWorldOneId = insertNode(regionOneId)
        nodeInWorldTwoId = insertNode(regionTwoId)

        store = JooqNpcZonesStore(dsl, mapper)
        gateway = NpcZoneAdminGatewayImpl(
            store = store,
            catalog = catalogFor(wolfDef),
            world = StubWorldQuery(),
        )
    }

    @Test
    fun `create node-scoped zone persists and returns row`() {
        val created = gateway.create(
            NpcZoneCreateSpec(
                worldId = WorldId(worldOneId),
                scope = NpcZoneScope.NODE,
                regionId = null,
                nodeId = NodeId(nodeInWorldOneId),
                weights = mapOf(wolfType to 3),
                maxConcurrent = 5,
                respawnTicks = 200,
                active = true,
                createdBy = admin,
                createdAtTick = 100L,
            )
        )

        val loaded = store.findById(created.zoneId)
        assertNotNull(loaded)
        assertEquals(NpcZoneScope.NODE, loaded.scope)
        assertEquals(NodeId(nodeInWorldOneId), loaded.nodeId)
        assertEquals(mapOf(wolfType to 3), loaded.weights)
        assertEquals(5, loaded.maxConcurrent)
    }

    @Test
    fun `create rejects unknown NPC types`() {
        assertThrows<NpcZoneAdminError.UnknownNpcTypes> {
            gateway.create(
                spec(
                    scope = NpcZoneScope.NODE,
                    nodeId = NodeId(nodeInWorldOneId),
                    weights = mapOf(NpcType("PHANTOM") to 1),
                )
            )
        }
    }

    @Test
    fun `create rejects node belonging to a different world`() {
        assertThrows<NpcZoneAdminError.WorldMismatch> {
            gateway.create(
                spec(
                    scope = NpcZoneScope.NODE,
                    nodeId = NodeId(nodeInWorldTwoId),
                    weights = mapOf(wolfType to 1),
                )
            )
        }
    }

    @Test
    fun `create rejects empty weights`() {
        assertThrows<NpcZoneAdminError.EmptyWeights> {
            gateway.create(
                spec(scope = NpcZoneScope.NODE, nodeId = NodeId(nodeInWorldOneId), weights = emptyMap())
            )
        }
    }

    @Test
    fun `patch applies weights and max_concurrent updates`() {
        val created = gateway.create(
            spec(
                scope = NpcZoneScope.NODE,
                nodeId = NodeId(nodeInWorldOneId),
                weights = mapOf(wolfType to 1),
                maxConcurrent = 1,
            )
        )

        val patched = gateway.patch(
            created.zoneId,
            NpcZonePatch(
                weights = MaybeSet.Set(mapOf(wolfType to 5)),
                maxConcurrent = MaybeSet.Set(7),
                respawnTicks = MaybeSet.Set(null),
                active = MaybeSet.Set(false),
            ),
        )

        assertEquals(mapOf(wolfType to 5), patched.weights)
        assertEquals(7, patched.maxConcurrent)
        assertNull(patched.respawnTicks)
        assertFalse(patched.active)
    }

    @Test
    fun `delete removes the row`() {
        val created = gateway.create(
            spec(scope = NpcZoneScope.NODE, nodeId = NodeId(nodeInWorldOneId), weights = mapOf(wolfType to 1))
        )

        val removed = gateway.delete(created.zoneId)

        assertEquals(true, removed)
        assertNull(store.findById(created.zoneId))
    }

    @Test
    fun `list returns only zones for that world`() {
        gateway.create(spec(scope = NpcZoneScope.NODE, nodeId = NodeId(nodeInWorldOneId), weights = mapOf(wolfType to 1)))
        gateway.create(
            spec(
                worldId = WorldId(worldTwoId),
                scope = NpcZoneScope.NODE,
                nodeId = NodeId(nodeInWorldTwoId),
                weights = mapOf(wolfType to 1),
            )
        )

        val worldOneZones = gateway.list(WorldId(worldOneId))

        assertEquals(1, worldOneZones.size)
        assertEquals(NodeId(nodeInWorldOneId), worldOneZones.single().nodeId)
    }

    private fun spec(
        worldId: WorldId = WorldId(worldOneId),
        scope: NpcZoneScope,
        nodeId: NodeId? = null,
        regionId: RegionId? = null,
        weights: Map<NpcType, Int>,
        maxConcurrent: Int = 1,
    ) = NpcZoneCreateSpec(
        worldId = worldId,
        scope = scope,
        regionId = regionId,
        nodeId = nodeId,
        weights = weights,
        maxConcurrent = maxConcurrent,
        respawnTicks = null,
        active = true,
        createdBy = admin,
        createdAtTick = 100L,
    )

    private fun catalogFor(vararg defs: NpcDef): NpcCatalog {
        val byType = defs.associateBy { it.type }
        return object : NpcCatalog {
            override fun byType(type: NpcType): NpcDef? = byType[type]
            override fun all(): Collection<NpcDef> = byType.values
            override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
        }
    }

    private inner class StubWorldQuery : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = when (id.value) {
            nodeInWorldOneId -> Node(id, RegionId(regionOneId), 0, 0, Terrain.PLAINS, emptySet())
            nodeInWorldTwoId -> Node(id, RegionId(regionTwoId), 0, 0, Terrain.PLAINS, emptySet())
            else -> null
        }
        override fun region(id: RegionId): Region? = when (id.value) {
            regionOneId -> region(id, WorldId(worldOneId))
            regionTwoId -> region(id, WorldId(worldTwoId))
            else -> null
        }
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<Npc>> = emptyMap()
    }

    private fun region(id: RegionId, worldId: WorldId) = Region(
        id = id, worldId = worldId, sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private fun insertWorld(name: String): Long =
        dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "$name-${System.nanoTime()}")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

    private fun insertRegion(worldId: Long): Long =
        dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, (System.nanoTime() % Int.MAX_VALUE).toInt())
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!

    private fun insertNode(regionId: Long): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, (System.nanoTime() % Int.MAX_VALUE).toInt())
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "FOREST")
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
}
