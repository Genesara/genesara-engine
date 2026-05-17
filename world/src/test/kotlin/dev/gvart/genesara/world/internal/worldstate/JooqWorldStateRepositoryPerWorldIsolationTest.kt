package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.killstreaks.KillStreakStore
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache
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
class JooqWorldStateRepositoryPerWorldIsolationTest {

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
    private lateinit var staticConfig: WorldStaticConfig
    private lateinit var presence: JooqWorldOnlinePresence

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @BeforeEach
    fun resetState() {
        dsl.truncate(AGENT_INVENTORY).cascade().execute()
        dsl.truncate(AGENT_BODIES).cascade().execute()
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()

        staticConfig = WorldStaticConfig(dsl, mapper)
        presence = JooqWorldOnlinePresence(dsl)
        repository = JooqWorldStateRepository(dsl, staticConfig, NoopKillStreakStore, InMemoryVisionBlockerCache())
    }

    private object NoopKillStreakStore : KillStreakStore {
        override fun byAgents(agents: Set<AgentId>): Map<AgentId, AgentKillStreak> = emptyMap()
        override fun save(agent: AgentId, streak: AgentKillStreak) = Unit
        override fun delete(agent: AgentId) = Unit
    }

    @Test
    fun `per-tick load fetches only the queried world's rows`() {
        val (worldA, nodeA) = seedSingleNodeWorld("world-a", regionSphereIndex = 0)
        val (worldB, nodeB) = seedSingleNodeWorld("world-b", regionSphereIndex = 1)
        staticConfig.reload()

        val agentA = AgentId(UUID.randomUUID())
        val agentB = AgentId(UUID.randomUUID())

        val baseBody = AgentBody(
            hp = 50, maxHp = 100, stamina = 30, maxStamina = 50, mana = 0, maxMana = 0,
            hunger = 100, maxHunger = 100, thirst = 100, maxThirst = 100, sleep = 100, maxSleep = 100,
        )

        repository.save(
            worldA,
            WorldState.EMPTY.copy(
                positions = mapOf(agentA to nodeA),
                bodies = mapOf(agentA to baseBody),
            ),
        )
        repository.save(
            worldB,
            WorldState.EMPTY.copy(
                positions = mapOf(agentB to nodeB),
                bodies = mapOf(agentB to baseBody),
            ),
        )

        val storedWorldIdA = dsl.select(AGENT_POSITIONS.WORLD_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agentA.id))
            .fetchOne(AGENT_POSITIONS.WORLD_ID)
        val storedWorldIdB = dsl.select(AGENT_POSITIONS.WORLD_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agentB.id))
            .fetchOne(AGENT_POSITIONS.WORLD_ID)
        assertEquals(worldA.value, storedWorldIdA, "agent A's denormalized world_id is set on save")
        assertEquals(worldB.value, storedWorldIdB, "agent B's denormalized world_id is set on save")

        val onlineA = presence.onlineIn(worldA)
        val onlineB = presence.onlineIn(worldB)
        assertEquals(setOf(agentA), onlineA, "presence must scope agents to their world")
        assertEquals(setOf(agentB), onlineB, "presence must scope agents to their world")

        val stateA = repository.load(worldA, onlineA)
        assertEquals(mapOf(agentA to nodeA), stateA.positions, "world A load must contain only agent A")
        assertTrue(agentB !in stateA.positions, "world A load must not include world B's agent")
        assertTrue(agentA in stateA.bodies, "world A load returns agent A's body")
        assertTrue(agentB !in stateA.bodies, "world A load must not include world B's body")

        val stateB = repository.load(worldB, onlineB)
        assertEquals(mapOf(agentB to nodeB), stateB.positions, "world B load must contain only agent B")
        assertTrue(agentA !in stateB.positions, "world B load must not include world A's agent")
        assertTrue(agentB in stateB.bodies, "world B load returns agent B's body")
        assertTrue(agentA !in stateB.bodies, "world B load must not include world A's body")
    }

    @Test
    fun `tombstone scoped to worldId leaves the other world's positions active`() {
        val (worldA, nodeA) = seedSingleNodeWorld("world-a", regionSphereIndex = 0)
        val (worldB, nodeB) = seedSingleNodeWorld("world-b", regionSphereIndex = 1)
        staticConfig.reload()

        val agentA = AgentId(UUID.randomUUID())
        val agentB = AgentId(UUID.randomUUID())

        repository.save(
            worldA,
            WorldState.EMPTY.copy(core = WorldState.EMPTY.core.copy(positions = mapOf(agentA to nodeA))),
        )
        repository.save(
            worldB,
            WorldState.EMPTY.copy(core = WorldState.EMPTY.core.copy(positions = mapOf(agentB to nodeB))),
        )

        // World A saves with no positions: world A's agent is tombstoned, world B is untouched.
        repository.save(worldA, WorldState.EMPTY)

        assertEquals(emptySet(), presence.onlineIn(worldA), "world A's tombstone removed agent A from presence")
        assertEquals(setOf(agentB), presence.onlineIn(worldB), "world B's agent is unaffected by world A's save")
    }

    private data class SeededWorld(val worldId: WorldId, val nodeId: dev.gvart.genesara.world.NodeId)

    private fun seedSingleNodeWorld(name: String, regionSphereIndex: Int): SeededWorld {
        val worldIdValue = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

        val regionIdValue = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldIdValue)
            .set(REGIONS.SPHERE_INDEX, regionSphereIndex)
            .set(REGIONS.BIOME, "PLAINS")
            .set(REGIONS.CLIMATE, "OCEANIC")
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!

        val nodeIdValue = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionIdValue)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "PLAINS")
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!

        return SeededWorld(WorldId(worldIdValue), dev.gvart.genesara.world.NodeId(nodeIdValue))
    }
}
