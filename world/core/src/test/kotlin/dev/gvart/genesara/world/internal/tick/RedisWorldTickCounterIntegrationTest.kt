package dev.gvart.genesara.world.internal.tick

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLD_TICK
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

@Testcontainers
class RedisWorldTickCounterIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("world_it")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

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

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var mirror: JooqWorldTickMirror
    private lateinit var writer: WorldTickMirrorWriter
    private lateinit var counter: RedisWorldTickCounter

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()

        dsl.deleteFrom(WORLD_TICK).execute()
        dsl.deleteFrom(WORLDS).execute()

        mirror = JooqWorldTickMirror(dsl)
        writer = WorldTickMirrorWriter(mirror)
        counter = RedisWorldTickCounter(template, mirror, writer)
    }

    @AfterEach
    fun tearDown() {
        writer.shutdown()
        connectionFactory.destroy()
    }

    @Test
    fun `incrementAndGet is monotonic per world and worlds do not share a counter`() {
        val worldA = seedWorld("world-a")
        val worldB = seedWorld("world-b")

        assertEquals(1L, counter.incrementAndGet(worldA))
        assertEquals(2L, counter.incrementAndGet(worldA))
        assertEquals(1L, counter.incrementAndGet(worldB), "world B starts at its own 1, not shared with A")
        assertEquals(3L, counter.incrementAndGet(worldA))
        assertEquals(2L, counter.incrementAndGet(worldB))
    }

    @Test
    fun `Postgres mirror catches up to the latest Redis value`() {
        val world = seedWorld("world-a")

        repeat(5) { counter.incrementAndGet(world) }

        // Single-thread mirror writer; shutdown awaits in-flight submits.
        writer.shutdown()

        assertEquals(5L, mirror.read(world))
    }

    @Test
    fun `cold-start seeds Redis to the persisted Postgres tick when Redis is empty`() {
        val world = seedWorld("world-a")

        // Pretend a previous lifetime advanced this world to tick 42 in Postgres.
        mirror.write(world, 42L)
        // Redis is flushed (BeforeEach) — fresh seedIfNeeded must pick up DB state.

        assertEquals(43L, counter.incrementAndGet(world), "first INCR after seed advances 42 -> 43")
        assertEquals(44L, counter.incrementAndGet(world))
    }

    @Test
    fun `cold-start prefers Redis when Redis is ahead of Postgres`() {
        val world = seedWorld("world-a")

        mirror.write(world, 5L)
        // Redis already at 100 (e.g. another pod just incremented it).
        template.opsForValue().set("world:${world.value}:tick", "100")

        assertEquals(101L, counter.incrementAndGet(world), "first INCR after seed advances 100 -> 101")
    }

    private fun seedWorld(name: String): WorldId {
        val id = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!
        dsl.insertInto(WORLD_TICK)
            .set(WORLD_TICK.WORLD_ID, id)
            .set(WORLD_TICK.TICK, 0L)
            .execute()
        return WorldId(id)
    }
}
