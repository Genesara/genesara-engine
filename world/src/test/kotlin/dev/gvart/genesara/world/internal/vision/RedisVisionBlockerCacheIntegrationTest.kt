package dev.gvart.genesara.world.internal.vision

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.buildings.BuildingDefinitionProperties
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.buildings.BuildingsConfiguration
import dev.gvart.genesara.world.internal.buildings.JooqBuildingGateStateStore
import dev.gvart.genesara.world.internal.buildings.JooqBuildingsStore
import dev.gvart.genesara.world.internal.jooq.tables.references.BUILDING_GATE_STATES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

@Testcontainers
class RedisVisionBlockerCacheIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("vision_it")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext
        private lateinit var catalog: BuildingsCatalog

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            catalog = AnnotationConfigApplicationContext().use { ctx ->
                ConfigurationPropertiesBindingPostProcessor.register(ctx)
                ctx.register(BuildingsConfiguration::class.java)
                ctx.refresh()
                BuildingsCatalog(ctx.getBean(BuildingDefinitionProperties::class.java))
            }
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var buildings: JooqBuildingsStore
    private lateinit var gateStates: BuildingGateStateStore
    private lateinit var cache: RedisVisionBlockerCache

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()

        dsl.deleteFrom(BUILDING_GATE_STATES).execute()
        dsl.deleteFrom(NODE_BUILDINGS).execute()

        buildings = JooqBuildingsStore(dsl)
        gateStates = JooqBuildingGateStateStore(dsl)
        cache = RedisVisionBlockerCache(template, dsl, catalog, gateStates)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `blockerHeights returns empty when no walls exist`() {
        cache.seedAll()
        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(1L), NodeId(2L))))
    }

    @Test
    fun `seedAll populates totals for active walls`() {
        insertActiveWall(NodeId(10L))
        insertActiveWall(NodeId(20L))

        cache.seedAll()

        val read = cache.blockerHeights(setOf(NodeId(10L), NodeId(20L), NodeId(99L)))
        assertEquals(mapOf(NodeId(10L) to 1, NodeId(20L) to 1), read)
    }

    @Test
    fun `seedAll stacks multiple active walls on the same node`() {
        insertActiveWall(NodeId(10L))
        insertActiveWall(NodeId(10L))

        cache.seedAll()

        assertEquals(mapOf(NodeId(10L) to 2), cache.blockerHeights(setOf(NodeId(10L))))
    }

    @Test
    fun `seedAll skips UNDER_CONSTRUCTION walls`() {
        insertWall(NodeId(10L), status = BuildingStatus.UNDER_CONSTRUCTION)

        cache.seedAll()

        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(10L))))
    }

    @Test
    fun `seedAll counts CLOSED gates but not OPEN gates`() {
        val closedGate = insertActiveGate(NodeId(10L))
        val openGate = insertActiveGate(NodeId(20L))
        gateStates.insertClosed(closedGate)
        gateStates.insertClosed(openGate)
        gateStates.toggle(openGate)

        cache.seedAll()

        assertEquals(
            mapOf(NodeId(10L) to 1),
            cache.blockerHeights(setOf(NodeId(10L), NodeId(20L))),
        )
    }

    @Test
    fun `recomputeForNode reflects new walls without a full re-seed`() {
        cache.seedAll()
        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(10L))))

        insertActiveWall(NodeId(10L))
        cache.recomputeForNode(NodeId(10L))

        assertEquals(mapOf(NodeId(10L) to 1), cache.blockerHeights(setOf(NodeId(10L))))
    }

    @Test
    fun `recomputeForNode deletes the field when the new total is zero`() {
        insertActiveWall(NodeId(10L))
        cache.seedAll()
        assertEquals(mapOf(NodeId(10L) to 1), cache.blockerHeights(setOf(NodeId(10L))))

        dsl.deleteFrom(NODE_BUILDINGS).execute()
        cache.recomputeForNode(NodeId(10L))

        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(10L))))
    }

    @Test
    fun `flush wipes the hash entirely`() {
        insertActiveWall(NodeId(10L))
        cache.seedAll()
        assertEquals(mapOf(NodeId(10L) to 1), cache.blockerHeights(setOf(NodeId(10L))))

        cache.flush()

        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(10L))))
    }

    @Test
    fun `seedAll wipes stale entries when the DB no longer has blockers`() {
        insertActiveWall(NodeId(10L))
        cache.seedAll()
        assertEquals(mapOf(NodeId(10L) to 1), cache.blockerHeights(setOf(NodeId(10L))))

        dsl.deleteFrom(NODE_BUILDINGS).execute()
        cache.seedAll()

        assertEquals(emptyMap(), cache.blockerHeights(setOf(NodeId(10L))))
    }

    private fun insertActiveWall(node: NodeId): UUID =
        insertWall(node, status = BuildingStatus.ACTIVE)

    private fun insertWall(node: NodeId, status: BuildingStatus): UUID {
        val id = UUID.randomUUID()
        buildings.insert(
            Building(
                instanceId = id,
                nodeId = node,
                type = BuildingType.WOODEN_WALL,
                status = status,
                builtByAgentId = AgentId(UUID.randomUUID()),
                builtAtTick = 1L,
                lastProgressTick = 1L,
                progressSteps = if (status == BuildingStatus.ACTIVE) 8 else 1,
                totalSteps = 8,
                hpCurrent = 120,
                hpMax = 120,
            ),
        )
        return id
    }

    private fun insertActiveGate(node: NodeId): UUID {
        val id = UUID.randomUUID()
        buildings.insert(
            Building(
                instanceId = id,
                nodeId = node,
                type = BuildingType.GATE,
                status = BuildingStatus.ACTIVE,
                builtByAgentId = AgentId(UUID.randomUUID()),
                builtAtTick = 1L,
                lastProgressTick = 1L,
                progressSteps = 10,
                totalSteps = 10,
                hpCurrent = 120,
                hpMax = 120,
            ),
        )
        return id
    }
}
