package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AssignClassOutcome
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import dev.gvart.genesara.player.RecordClassOfferOutcome
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

@Testcontainers
class JooqAgentRegistryClassChoiceIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("class_it")
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
    private val offer = ClassOffer(AgentClass.SOLDIER, AgentClass.SCOUT)

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_PROFILES).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
    }

    @Test
    fun `recordPendingClassChoice writes both columns and is reflected on find`() {
        val registry = registry()
        val agent = registry.register(owner, "Recruit")

        val outcome = registry.recordPendingClassChoice(agent.id, offer)

        assertEquals(RecordClassOfferOutcome.Recorded, outcome)
        val row = readAgent(agent.id)
        assertEquals("SOLDIER", row[AGENTS.OFFERED_CLASS_A])
        assertEquals("SCOUT", row[AGENTS.OFFERED_CLASS_B])
        assertNull(row[AGENTS.CLASS_ID])
        val refreshed = registry.find(agent.id)
        assertEquals(offer, refreshed?.offeredClasses)
    }

    @Test
    fun `recordPendingClassChoice rejects when an offer is already pending`() {
        val registry = registry()
        val agent = registry.register(owner, "Doubler")
        registry.recordPendingClassChoice(agent.id, offer)

        val outcome = registry.recordPendingClassChoice(
            agent.id,
            ClassOffer(AgentClass.HUNTER, AgentClass.MEDIC),
        )

        val rejection = assertIs<RecordClassOfferOutcome.AlreadyOffered>(outcome)
        assertEquals(offer, rejection.existing, "the original offer is preserved")
        val row = readAgent(agent.id)
        assertEquals("SOLDIER", row[AGENTS.OFFERED_CLASS_A], "no overwrite")
    }

    @Test
    fun `recordPendingClassChoice rejects when the agent already has a class`() {
        val registry = registry()
        val agent = registry.register(owner, "Veteran")
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, AgentClass.MEDIC.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = registry.recordPendingClassChoice(agent.id, offer)

        assertEquals(RecordClassOfferOutcome.AlreadyClassed, outcome)
    }

    @Test
    fun `recordPendingClassChoice returns UnknownAgent for missing rows`() {
        val registry = registry()

        val outcome = registry.recordPendingClassChoice(AgentId(UUID.randomUUID()), offer)

        assertEquals(RecordClassOfferOutcome.UnknownAgent, outcome)
    }

    @Test
    fun `assignClass commits the chosen class and clears the offer columns`() {
        val registry = registry()
        val agent = registry.register(owner, "Picker")
        registry.recordPendingClassChoice(agent.id, offer)

        val outcome = registry.assignClass(agent.id, AgentClass.SCOUT)

        assertEquals(AssignClassOutcome.Assigned, outcome)
        val row = readAgent(agent.id)
        assertEquals("SCOUT", row[AGENTS.CLASS_ID])
        assertNull(row[AGENTS.OFFERED_CLASS_A])
        assertNull(row[AGENTS.OFFERED_CLASS_B])
        val refreshed = registry.find(agent.id)
        assertEquals(AgentClass.SCOUT, refreshed?.classId)
        assertNull(refreshed?.offeredClasses)
    }

    @Test
    fun `assignClass rejects when no offer is pending`() {
        val registry = registry()
        val agent = registry.register(owner, "Eager")

        val outcome = registry.assignClass(agent.id, AgentClass.SCOUT)

        assertEquals(AssignClassOutcome.NoPendingOffer, outcome)
        val row = readAgent(agent.id)
        assertNull(row[AGENTS.CLASS_ID])
    }

    @Test
    fun `assignClass rejects when the chosen class is not in the offer`() {
        val registry = registry()
        val agent = registry.register(owner, "Stray")
        registry.recordPendingClassChoice(agent.id, offer)

        val outcome = registry.assignClass(agent.id, AgentClass.RESEARCHER)

        val rejection = assertIs<AssignClassOutcome.NotInOffer>(outcome)
        assertEquals(offer, rejection.pending)
        val row = readAgent(agent.id)
        assertNull(row[AGENTS.CLASS_ID], "no DB mutation on rejection")
        assertEquals("SOLDIER", row[AGENTS.OFFERED_CLASS_A])
    }

    @Test
    fun `assignClass rejects when the agent already has a class`() {
        val registry = registry()
        val agent = registry.register(owner, "Locked")
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, AgentClass.MEDIC.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val outcome = registry.assignClass(agent.id, AgentClass.SCOUT)

        val rejection = assertIs<AssignClassOutcome.AlreadyClassed>(outcome)
        assertEquals(AgentClass.MEDIC, rejection.existing)
    }

    @Test
    fun `assignClass returns UnknownAgent for missing rows`() {
        val registry = registry()

        val outcome = registry.assignClass(AgentId(UUID.randomUUID()), AgentClass.SCOUT)

        assertEquals(AssignClassOutcome.UnknownAgent, outcome)
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
