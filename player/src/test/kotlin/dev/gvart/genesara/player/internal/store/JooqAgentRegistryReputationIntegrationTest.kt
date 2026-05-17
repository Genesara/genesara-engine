package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.NoOpClassLookup
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end coverage for [JooqAgentRegistry.adjustAuthority] /
 * [adjustFame]. Pins that the row lock + read-modify-write path composes
 * additively, the default value is zero, and a missing agent returns null
 * (matching the [JooqAgentRegistry.applyDeathPenalty] convention).
 */
@Testcontainers
class JooqAgentRegistryReputationIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("reputation_it")
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
    fun `fresh agent starts at authority=0, fame=0`() {
        val registry = registry()
        val agent = registry.register(owner, "Newcomer")

        val fresh = assertNotNull(registry.find(agent.id))
        assertEquals(0, fresh.authority)
        assertEquals(0, fresh.fame)
    }

    @Test
    fun `adjustAuthority composes additively across calls`() {
        val registry = registry()
        val agent = registry.register(owner, "Diplomat")

        assertEquals(5, registry.adjustAuthority(agent.id, delta = 5))
        assertEquals(8, registry.adjustAuthority(agent.id, delta = 3))
        assertEquals(8, assertNotNull(registry.find(agent.id)).authority)
    }

    @Test
    fun `adjustFame accepts negative deltas`() {
        val registry = registry()
        val agent = registry.register(owner, "FallenStar")

        registry.adjustFame(agent.id, delta = 50)
        val afterDrop = assertNotNull(registry.adjustFame(agent.id, delta = -30))

        assertEquals(20, afterDrop)
        assertEquals(20, assertNotNull(registry.find(agent.id)).fame)
    }

    @Test
    fun `adjustAuthority and adjustFame mutate independent columns`() {
        val registry = registry()
        val agent = registry.register(owner, "BalancedRep")

        registry.adjustAuthority(agent.id, delta = 10)
        registry.adjustFame(agent.id, delta = 7)

        val row = assertNotNull(registry.find(agent.id))
        assertEquals(10, row.authority)
        assertEquals(7, row.fame)
    }

    @Test
    fun `adjustAuthority returns null for an unregistered agent`() {
        val registry = registry()
        assertNull(registry.adjustAuthority(AgentId(UUID.randomUUID()), delta = 5))
    }

    @Test
    fun `adjustFame returns null for an unregistered agent`() {
        val registry = registry()
        assertNull(registry.adjustFame(AgentId(UUID.randomUUID()), delta = 5))
    }

    @Test
    fun `adjustAuthority saturates rather than wrapping on near-Int_MAX delta`() {
        val registry = registry()
        val agent = registry.register(owner, "Overflow")

        registry.adjustAuthority(agent.id, delta = Int.MAX_VALUE - 5)
        // Without the Long-arithmetic guard the second call would wrap to a
        // large negative number; this pins the saturation behavior.
        val outcome = assertNotNull(registry.adjustAuthority(agent.id, delta = 100))
        assertEquals(Int.MAX_VALUE, outcome)
    }

    @Test
    fun `adjustFame saturates rather than wrapping on near-Int_MIN delta`() {
        val registry = registry()
        val agent = registry.register(owner, "DeepNegative")

        registry.adjustFame(agent.id, delta = Int.MIN_VALUE + 5)
        val outcome = assertNotNull(registry.adjustFame(agent.id, delta = -100))
        assertEquals(Int.MIN_VALUE, outcome)
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
        return JooqAgentRegistry(dsl, JooqProfileRepository(dsl), assigner, NoOpClassLookup)
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
