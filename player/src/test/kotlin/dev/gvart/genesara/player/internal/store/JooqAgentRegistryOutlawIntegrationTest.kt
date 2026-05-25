package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.OutlawState
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
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [JooqAgentRegistry.adjustMisconduct] /
 * [decayMisconductScores]. Pins the score+state column staying consistent
 * across writes, the schema's CHECK(>=0) holding under negative deltas,
 * and the batched decay path returning one outcome per affected row.
 */
@Testcontainers
class JooqAgentRegistryOutlawIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("outlaw_it")
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
    private val watchedAt = 25
    private val outlawAt = 100

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_PROFILES).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
    }

    @Test
    fun `fresh agent starts CLEAN with score 0`() {
        val registry = registry()
        val agent = registry.register(owner, "Newcomer")

        val fresh = assertNotNull(registry.find(agent.id))
        assertEquals(OutlawState.CLEAN, fresh.outlawState)
        assertEquals(0, fresh.outlawMisconductScore)
    }

    @Test
    fun `adjustMisconduct from clean to watched updates both score and state`() {
        val registry = registry()
        val agent = registry.register(owner, "Sinner")

        val outcome = assertNotNull(registry.adjustMisconduct(agent.id, delta = 30, watchedAt, outlawAt))
        assertEquals(OutlawState.CLEAN, outcome.oldState)
        assertEquals(OutlawState.WATCHED, outcome.newState)
        assertEquals(30, outcome.newScore)
        assertTrue(outcome.didTransition)

        val reread = assertNotNull(registry.find(agent.id))
        assertEquals(OutlawState.WATCHED, reread.outlawState)
        assertEquals(30, reread.outlawMisconductScore)
    }

    @Test
    fun `successive adjustMisconduct calls stack to OUTLAW`() {
        val registry = registry()
        val agent = registry.register(owner, "Repeat")

        registry.adjustMisconduct(agent.id, delta = 60, watchedAt, outlawAt)
        val outcome = assertNotNull(registry.adjustMisconduct(agent.id, delta = 50, watchedAt, outlawAt))

        assertEquals(OutlawState.WATCHED, outcome.oldState)
        assertEquals(OutlawState.OUTLAW, outcome.newState)
        assertEquals(110, outcome.newScore)
    }

    @Test
    fun `negative adjustMisconduct clamps score at zero — schema CHECK holds`() {
        val registry = registry()
        val agent = registry.register(owner, "Pardoned")

        registry.adjustMisconduct(agent.id, delta = 50, watchedAt, outlawAt)
        val outcome = assertNotNull(registry.adjustMisconduct(agent.id, delta = -1_000, watchedAt, outlawAt))

        assertEquals(0, outcome.newScore, "negative delta saturates at zero")
        assertEquals(OutlawState.CLEAN, outcome.newState)
    }

    @Test
    fun `same-bucket adjust still records old=new state — no transition flag`() {
        val registry = registry()
        val agent = registry.register(owner, "Stagnant")

        registry.adjustMisconduct(agent.id, delta = 30, watchedAt, outlawAt)
        val outcome = assertNotNull(registry.adjustMisconduct(agent.id, delta = 10, watchedAt, outlawAt))

        assertEquals(OutlawState.WATCHED, outcome.oldState)
        assertEquals(OutlawState.WATCHED, outcome.newState)
        assertEquals(false, outcome.didTransition)
    }

    @Test
    fun `adjustMisconduct returns null for an unregistered agent`() {
        val registry = registry()
        assertNull(registry.adjustMisconduct(AgentId(UUID.randomUUID()), delta = 5, watchedAt, outlawAt))
    }

    @Test
    fun `decayMisconductScores ignores clean agents and downshifts the rest`() {
        val registry = registry()
        val clean = registry.register(owner, "Clean")
        val watched = registry.register(owner, "Watched")
        val outlaw = registry.register(owner, "Outlaw")
        registry.adjustMisconduct(watched.id, delta = 25, watchedAt, outlawAt)
        registry.adjustMisconduct(outlaw.id, delta = 100, watchedAt, outlawAt)

        val outcomes = registry.decayMisconductScores(amount = 1, watchedAt, outlawAt)

        // Clean agent has score 0 → query skips them.
        assertEquals(2, outcomes.size)
        val byAgent = outcomes.associateBy { it.agentId }
        assertEquals(24, byAgent[watched.id]?.newScore)
        assertEquals(OutlawState.CLEAN, byAgent[watched.id]?.newState, "score 24 < watchedAt → downshift")
        assertEquals(99, byAgent[outlaw.id]?.newScore)
        assertEquals(OutlawState.WATCHED, byAgent[outlaw.id]?.newState, "score 99 < outlawAt → downshift")
        // Clean agent untouched.
        assertEquals(0, assertNotNull(registry.find(clean.id)).outlawMisconductScore)
    }

    @Test
    fun `decayMisconductScores noop when amount is zero`() {
        val registry = registry()
        val agent = registry.register(owner, "FrozenScore")
        registry.adjustMisconduct(agent.id, delta = 50, watchedAt, outlawAt)

        val outcomes = registry.decayMisconductScores(amount = 0, watchedAt, outlawAt)

        assertTrue(outcomes.isEmpty())
        assertEquals(50, assertNotNull(registry.find(agent.id)).outlawMisconductScore)
    }

    @Test
    fun `decayMisconductScores returns empty list when no flagged agents exist`() {
        val registry = registry()
        registry.register(owner, "AlwaysClean")

        val outcomes = registry.decayMisconductScores(amount = 1, watchedAt, outlawAt)

        assertTrue(outcomes.isEmpty())
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
