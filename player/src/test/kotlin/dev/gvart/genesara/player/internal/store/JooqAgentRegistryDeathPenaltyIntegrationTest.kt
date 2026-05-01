package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.AttributePointLoss
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end coverage for [JooqAgentRegistry.applyDeathPenalty]. Pins the
 * branch logic + DB-level mutations on a real Postgres so we catch any
 * future schema drift (e.g. a migration that renames XP_CURRENT) at test
 * time rather than runtime. Six branches:
 *  - partial bar (xpCurrent > 0)
 *  - partial bar with xpLossOnDeath capped at xpCurrent
 *  - empty bar with unspent pool
 *  - empty bar without unspent → highest-attribute decrement
 *  - empty bar with all attrs at the floor → no-op stat-wise
 *  - level-1 floor on de-level (no actual de-level fires, deleveled=false)
 */
@Testcontainers
class JooqAgentRegistryDeathPenaltyIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("death_it")
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
    fun `partial bar branch subtracts XP only and leaves level alone`() {
        val registry = registry()
        val agent = registry.register(owner, "Survivor")
        // Bump xpCurrent so we're on the partial-bar branch.
        dsl.update(AGENTS).set(AGENTS.XP_CURRENT, 70).where(AGENTS.ID.eq(agent.id.id)).execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        assertEquals(25, outcome.xpLost)
        assertEquals(false, outcome.deleveled)
        assertEquals(null, outcome.attributePointLost)
        val row = readAgent(agent.id)
        assertEquals(45, row[AGENTS.XP_CURRENT])
        assertEquals(1, row[AGENTS.LEVEL])
        assertEquals(5, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `partial bar branch caps xpLost at the agent's current XP`() {
        val registry = registry()
        val agent = registry.register(owner, "Almost-empty")
        dsl.update(AGENTS).set(AGENTS.XP_CURRENT, 5).where(AGENTS.ID.eq(agent.id.id)).execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        // Only 5 XP existed; that's all that's lost. We don't dip into the
        // de-level branch on the same call — a follow-up death from the now
        // empty-bar state would.
        assertEquals(5, outcome.xpLost)
        assertEquals(false, outcome.deleveled)
        assertEquals(0, readAgent(agent.id)[AGENTS.XP_CURRENT])
    }

    @Test
    fun `empty bar branch with unspent pool consumes a pool point and de-levels`() {
        val registry = registry()
        val agent = registry.register(owner, "Striver")
        // Level the agent up so the de-level has somewhere to go.
        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, 5)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.XP_TO_NEXT, 500)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 3)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        assertEquals(0, outcome.xpLost)
        assertEquals(true, outcome.deleveled)
        assertIs<AttributePointLoss.Unspent>(outcome.attributePointLost)
        val row = readAgent(agent.id)
        assertEquals(4, row[AGENTS.LEVEL])
        // xpToNext re-anchored on the new level: 4 * 100 = 400.
        assertEquals(400, row[AGENTS.XP_TO_NEXT])
        assertEquals(2, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `empty bar branch without unspent decrements the highest allocated attribute`() {
        val registry = registry()
        val agent = registry.register(owner, "Specialist")
        // Spend the unspent pool, push STR clearly highest, set up empty bar.
        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, 3)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 0)
            .set(AGENTS.STRENGTH, 12)
            .set(AGENTS.DEXTERITY, 5)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        assertEquals(true, outcome.deleveled)
        val loss = assertIs<AttributePointLoss.Allocated>(outcome.attributePointLost)
        assertEquals(Attribute.STRENGTH, loss.attribute)
        val row = readAgent(agent.id)
        assertEquals(11, row[AGENTS.STRENGTH], "highest attribute decremented by 1")
        assertEquals(5, row[AGENTS.DEXTERITY], "non-target stat untouched")
        assertEquals(0, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `attribute ties are broken by enum ordinal — STRENGTH wins over DEXTERITY at equal value`() {
        val registry = registry()
        val agent = registry.register(owner, "Balanced")
        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, 2)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 0)
            .set(AGENTS.STRENGTH, 10)
            .set(AGENTS.DEXTERITY, 10)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        val loss = assertIs<AttributePointLoss.Allocated>(outcome.attributePointLost)
        // STRENGTH has lower ordinal in Attribute.entries, so maxBy returns it
        // on a tie. Pin the rule so a future enum reorder is caught.
        assertEquals(Attribute.STRENGTH, loss.attribute)
    }

    @Test
    fun `level-1 agent on the empty-bar branch does not de-level (floor)`() {
        val registry = registry()
        val agent = registry.register(owner, "Newbie")
        // Default level=1, xpCurrent=0 — already on the empty-bar branch.

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        assertEquals(false, outcome.deleveled, "level-1 stays at 1 — no actual de-level")
        // Still consume a penalty point, though — the spec doesn't carve out
        // level-1 from the cost.
        assertIs<AttributePointLoss.Unspent>(outcome.attributePointLost)
        assertEquals(1, readAgent(agent.id)[AGENTS.LEVEL])
        assertEquals(4, readAgent(agent.id)[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `empty bar with all attributes at the floor — no stat decrement, attribute loss reported as null`() {
        val registry = registry()
        val agent = registry.register(owner, "Glass")
        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, 2)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 0)
            .set(AGENTS.STRENGTH, 1)
            .set(AGENTS.DEXTERITY, 1)
            .set(AGENTS.CONSTITUTION, 1)
            .set(AGENTS.PERCEPTION, 1)
            .set(AGENTS.INTELLIGENCE, 1)
            .set(AGENTS.LUCK, 1)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = assertNotNull(registry.applyDeathPenalty(agent.id, xpLossOnDeath = 25))

        assertEquals(true, outcome.deleveled, "level > 1 → genuine de-level fires")
        assertNull(outcome.attributePointLost, "all stats at floor — no decrement, honest null")
        val row = readAgent(agent.id)
        assertEquals(1, row[AGENTS.LEVEL])
        assertEquals(1, row[AGENTS.STRENGTH])
    }

    @Test
    fun `applyDeathPenalty returns null for an unregistered agent`() {
        val registry = registry()
        assertNull(registry.applyDeathPenalty(AgentId(UUID.randomUUID()), xpLossOnDeath = 25))
    }

    @Test
    fun `applyDeathPenalty rejects a negative xpLossOnDeath`() {
        val registry = registry()
        val agent = registry.register(owner, "Sanity")
        try {
            registry.applyDeathPenalty(agent.id, xpLossOnDeath = -1)
            error("expected IllegalArgumentException for negative xpLoss")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
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

    private fun readAgent(id: AgentId) =
        assertNotNull(dsl.selectFrom(AGENTS).where(AGENTS.ID.eq(id.id)).fetchOne())

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
