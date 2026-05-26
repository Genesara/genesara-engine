package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.killstreaks.KillStreakStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import java.util.UUID
import kotlin.test.assertEquals
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
class JooqWorldStateRepositoryActiveLifecycleIntegrationTest {

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
        repository = JooqWorldStateRepository(dsl, staticConfig, NoopKillStreakStore, InMemoryVisionBlockerCache())
    }

    private object NoopKillStreakStore : KillStreakStore {
        override fun byAgents(agents: Set<AgentId>): Map<AgentId, AgentKillStreak> = emptyMap()
        override fun save(agent: AgentId, streak: AgentKillStreak) = Unit
        override fun delete(agent: AgentId) = Unit
    }

    @Test
    fun `spawn then death then respawn drives agent_positions active true to false to true`() {
        val (worldId, nodeId) = seedSingleNodeWorld("lifecycle-world")
        staticConfig.reload()

        val agent = AgentId(UUID.randomUUID())
        val body = AgentBody(
            hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0,
            hunger = 100, maxHunger = 100, thirst = 100, maxThirst = 100, sleep = 100, maxSleep = 100,
        )

        repository.save(
            worldId,
            WorldState.EMPTY.copy(
                positions = mapOf(agent to nodeId),
                bodies = mapOf(agent to body),
            ),
        )
        assertEquals(true, activeFlag(agent), "spawn writes active=true")

        repository.save(
            worldId,
            WorldState.EMPTY.copy(
                positions = emptyMap(),
                bodies = mapOf(agent to body.copy(hp = 0)),
            ),
        )
        assertEquals(false, activeFlag(agent), "death removes the agent from positions, tombstone flips active=false")

        repository.save(
            worldId,
            WorldState.EMPTY.copy(
                positions = mapOf(agent to nodeId),
                bodies = mapOf(agent to body),
            ),
        )
        assertEquals(true, activeFlag(agent), "respawn re-adds the agent, upsert flips active=true")
    }

    private fun activeFlag(agent: AgentId): Boolean? =
        dsl.select(AGENT_POSITIONS.ACTIVE)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agent.id))
            .fetchOne(AGENT_POSITIONS.ACTIVE)

    private data class SeededWorld(val worldId: WorldId, val nodeId: NodeId)

    private fun seedSingleNodeWorld(name: String): SeededWorld {
        val worldIdValue = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

        val regionIdValue = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldIdValue)
            .set(REGIONS.SPHERE_INDEX, 0)
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

        return SeededWorld(WorldId(worldIdValue), NodeId(nodeIdValue))
    }
}
