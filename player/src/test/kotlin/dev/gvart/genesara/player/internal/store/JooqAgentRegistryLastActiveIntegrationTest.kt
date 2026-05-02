package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PROFILES
import dev.gvart.genesara.player.internal.race.RaceAssigner
import dev.gvart.genesara.player.internal.race.RaceDefinitionProperties
import dev.gvart.genesara.player.internal.race.RandomSource
import dev.gvart.genesara.player.internal.testsupport.PlayerFlyway
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class JooqAgentRegistryLastActiveIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("last_active_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = PlayerFlyway.pooledDataSource(postgres)
            PlayerFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val owner = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_PROFILES).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
    }

    @Test
    fun `findLastActive returns null for an agent that has never been touched`() {
        val registry = registry()
        val agent = registry.register(owner, "Newcomer")

        assertNull(registry.findLastActive(agent.id))
    }

    @Test
    fun `saveLastActive then findLastActive round-trips an Instant rounded to microseconds`() {
        val registry = registry()
        val agent = registry.register(owner, "Active")
        val ts = Instant.parse("2026-05-02T12:34:56.123456Z")

        registry.saveLastActive(mapOf(agent.id to ts))

        assertEquals(ts, registry.findLastActive(agent.id))
    }

    @Test
    fun `saveLastActive overwrites the prior value`() {
        val registry = registry()
        val agent = registry.register(owner, "Updated")
        val older = Instant.parse("2026-05-02T10:00:00Z")
        val newer = Instant.parse("2026-05-02T11:00:00Z")

        registry.saveLastActive(mapOf(agent.id to older))
        registry.saveLastActive(mapOf(agent.id to newer))

        assertEquals(newer, registry.findLastActive(agent.id))
    }

    @Test
    fun `findLastActiveBatch only returns agents with a non-null last_active_at`() {
        val registry = registry()
        val active = registry.register(owner, "Touched")
        val never = registry.register(owner, "Quiet")
        val ts = Instant.parse("2026-05-02T12:00:00Z")
        registry.saveLastActive(mapOf(active.id to ts))

        val batch = registry.findLastActiveBatch(listOf(active.id, never.id))

        assertEquals(mapOf(active.id to ts), batch)
    }

    @Test
    fun `saveLastActive on an empty map is a no-op`() {
        val registry = registry()

        registry.saveLastActive(emptyMap())
    }

    @Test
    fun `findLastActiveBatch on an empty input is an empty result with no DB call`() {
        val registry = registry()

        assertEquals(emptyMap(), registry.findLastActiveBatch(emptyList()))
    }

    private fun registry(): JooqAgentRegistry {
        val race = Race(
            id = RaceId("test_race"),
            displayName = "Test",
            weight = 1,
            attributeMods = AttributeMods.NONE,
            description = "",
        )
        val lookup = SingleRaceLookup(race)
        val props = RaceDefinitionProperties(defaultId = race.id.value)
        val assigner = RaceAssigner(lookup, props, FixedRandom)
        return JooqAgentRegistry(dsl, JooqProfileRepository(dsl), assigner)
    }

    private class SingleRaceLookup(private val race: Race) : RaceLookup {
        override fun byId(id: RaceId): Race? = if (id == race.id) race else null
        override fun all(): List<Race> = listOf(race)
    }

    private object FixedRandom : RandomSource {
        override fun nextInt(boundExclusive: Int): Int = 0
    }

    private class JooqProfileRepository(private val dsl: DSLContext) : AgentProfileRepository {
        override fun save(profile: AgentProfile) {
            dsl.insertInto(AGENT_PROFILES)
                .set(AGENT_PROFILES.AGENT_ID, profile.id.id)
                .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
                .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
                .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
                .execute()
        }
    }
}
