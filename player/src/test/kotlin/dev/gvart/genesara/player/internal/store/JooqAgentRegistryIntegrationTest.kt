package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeDerivation
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end test for the registration flow: assignment → mod application → derivation →
 * persistence to both `agents` and `agent_profiles`.
 *
 * Builds a real `JooqAgentRegistry` against a Testcontainers Postgres instance with the
 * player module's migrations applied, plus a stub `RaceAssigner` (driven by a fixed
 * `RandomSource`) so we can pin which race the agent gets without depending on the
 * weighted-pick math (that's covered in `RaceAssignerTest`).
 */
@Testcontainers
class JooqAgentRegistryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("player_it")
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
        // Order matters: agent_profiles → agents (FK ON DELETE CASCADE handles the link, but
        // restartIdentity keeps tests isolated even if a future migration adds a sequence).
        dsl.truncate(AGENT_PROFILES).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
    }

    @Test
    fun `register persists race id, applies mods, derives profile pools, stores all default fields`() {
        val steppe = Race(
            id = RaceId("human_steppe"),
            displayName = "Steppe-born",
            weight = 1,
            attributeMods = AttributeMods(strength = -1, dexterity = 1, perception = 1),
            description = "test",
        )
        val registry = registryWithFixedRace(steppe)

        val agent = registry.register(owner, "Komar")

        // Returned aggregate matches what was persisted.
        assertEquals(RaceId("human_steppe"), agent.race)
        assertEquals(1, agent.level)
        assertEquals(0, agent.xpCurrent)
        assertEquals(100, agent.xpToNext)
        assertEquals(5, agent.unspentAttributePoints)
        // Steppe mods: str -1 (clamped to MIN=1), dex +1, perception +1; rest stay at 1.
        assertEquals(
            AgentAttributes(
                strength = 1, dexterity = 2, constitution = 1,
                perception = 2, intelligence = 1, luck = 1,
            ),
            agent.attributes,
        )

        // `agents` row has the same fields.
        val row = assertNotNull(
            dsl.selectFrom(AGENTS).where(AGENTS.ID.eq(agent.id.id)).fetchOne()
        )
        assertEquals("human_steppe", row[AGENTS.RACE_ID])
        assertEquals(1, row[AGENTS.LEVEL])
        assertEquals(0, row[AGENTS.XP_CURRENT])
        assertEquals(100, row[AGENTS.XP_TO_NEXT])
        assertEquals(5, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
        assertEquals(1, row[AGENTS.STRENGTH])
        assertEquals(2, row[AGENTS.DEXTERITY])
        assertEquals(1, row[AGENTS.CONSTITUTION])
        assertEquals(2, row[AGENTS.PERCEPTION])
        assertEquals(1, row[AGENTS.INTELLIGENCE])
        assertEquals(1, row[AGENTS.LUCK])

        // `agent_profiles` row matches the AttributeDerivation output for the post-mods attributes.
        val expectedPools = AttributeDerivation.deriveMaxPools(agent.attributes)
        val profile = assertNotNull(
            dsl.selectFrom(AGENT_PROFILES).where(AGENT_PROFILES.AGENT_ID.eq(agent.id.id)).fetchOne()
        )
        assertEquals(expectedPools.maxHp, profile[AGENT_PROFILES.MAX_HP])
        assertEquals(expectedPools.maxStamina, profile[AGENT_PROFILES.MAX_STAMINA])
        assertEquals(expectedPools.maxMana, profile[AGENT_PROFILES.MAX_MANA])
    }

    @Test
    fun `register clamps post-mod attributes to the MIN_ATTRIBUTE floor`() {
        val brittle = Race(
            id = RaceId("brittle"),
            displayName = "Brittle",
            weight = 1,
            attributeMods = AttributeMods(
                strength = -10, dexterity = -10, constitution = -10,
                perception = -10, intelligence = -10, luck = -10,
            ),
            description = "stress test for the clamp",
        )
        val registry = registryWithFixedRace(brittle)

        val agent = registry.register(owner, "Glass Cannon")

        // Every attribute should have hit the floor of 1.
        val all = listOf(
            agent.attributes.strength, agent.attributes.dexterity, agent.attributes.constitution,
            agent.attributes.perception, agent.attributes.intelligence, agent.attributes.luck,
        )
        assertEquals(List(6) { AgentAttributes.MIN_ATTRIBUTE }, all)

        // Profile pools should be derived from the clamped (1..1..1..1..1..1) attributes.
        val expectedPools = AttributeDerivation.deriveMaxPools(AgentAttributes.DEFAULT)
        val profile = assertNotNull(
            dsl.selectFrom(AGENT_PROFILES).where(AGENT_PROFILES.AGENT_ID.eq(agent.id.id)).fetchOne()
        )
        assertEquals(expectedPools.maxHp, profile[AGENT_PROFILES.MAX_HP])
        assertEquals(expectedPools.maxStamina, profile[AGENT_PROFILES.MAX_STAMINA])
        assertEquals(expectedPools.maxMana, profile[AGENT_PROFILES.MAX_MANA])
    }

    @Test
    fun `find round-trips an agent including all new fields`() {
        val race = Race(
            id = RaceId("round_trip"),
            displayName = "RT",
            weight = 1,
            attributeMods = AttributeMods.NONE,
            description = "",
        )
        val registry = registryWithFixedRace(race)

        val written = registry.register(owner, "Trip")
        val read = assertNotNull(registry.find(written.id))

        assertEquals(written, read)
    }

    private fun registryWithFixedRace(race: Race): JooqAgentRegistry {
        val lookup = SingleRaceLookup(race)
        val props = RaceDefinitionProperties(defaultId = race.id.value)
        val assigner = RaceAssigner(lookup, props, FixedRandom)
        val profileRepo = JooqProfileRepository(dsl)
        return JooqAgentRegistry(dsl, profileRepo, assigner)
    }

    private class SingleRaceLookup(private val race: Race) : RaceLookup {
        override fun byId(id: RaceId): Race? = if (id == race.id) race else null
        override fun all(): List<Race> = listOf(race)
    }

    private object FixedRandom : RandomSource {
        override fun nextInt(boundExclusive: Int): Int = 0
    }

    /**
     * Inline jOOQ-backed `AgentProfileRepository` so the test doesn't have to spin up Spring
     * to get one. Mirrors the production `JooqAgentProfileStore.save(...)` shape; if that
     * implementation diverges, this test will need to track it.
     */
    private class JooqProfileRepository(private val dsl: DSLContext) : AgentProfileRepository {
        override fun save(profile: AgentProfile) {
            dsl.insertInto(AGENT_PROFILES)
                .set(AGENT_PROFILES.AGENT_ID, profile.id.id)
                .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
                .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
                .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
                .onConflict(AGENT_PROFILES.AGENT_ID)
                .doUpdate()
                .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
                .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
                .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
                .execute()
        }
    }
}
