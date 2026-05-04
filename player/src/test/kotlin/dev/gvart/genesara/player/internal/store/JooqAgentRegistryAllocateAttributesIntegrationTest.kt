package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMilestoneCrossing
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentRegistryAllocateAttributesIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("alloc_it")
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
    fun `single-attribute allocation bumps the column and decrements unspent`() {
        val registry = registry()
        val agent = registry.register(owner, "Brawler")

        val outcome = registry.allocateAttributes(agent.id, mapOf(Attribute.STRENGTH to 3))

        val allocated = assertIs<AllocateAttributesOutcome.Allocated>(outcome)
        assertEquals(4, allocated.attributes.strength)
        assertEquals(2, allocated.remainingUnspent)
        assertTrue(allocated.crossedMilestones.isEmpty())

        val row = readAgent(agent.id)
        assertEquals(4, row[AGENTS.STRENGTH])
        assertEquals(1, row[AGENTS.DEXTERITY], "untouched attributes stay at default")
        assertEquals(2, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `constitution allocation recomputes max_hp and max_stamina via AttributeDerivation`() {
        val registry = registry()
        val agent = registry.register(owner, "Tank")

        registry.allocateAttributes(agent.id, mapOf(Attribute.CONSTITUTION to 4))

        val profile = readProfile(agent.id)
        // CON 5 → maxHp = HP_BASE 50 + 5 × HP_PER_CON 10 = 100.
        assertEquals(100, profile[AGENT_PROFILES.MAX_HP])
        // (CON 5 + DEX 1) × STAMINA_PER_PT 5 + STAMINA_BASE 30 = 60.
        assertEquals(60, profile[AGENT_PROFILES.MAX_STAMINA])
        // INT unchanged → MANA_PER_INT 5 × 1 = 5.
        assertEquals(5, profile[AGENT_PROFILES.MAX_MANA])
    }

    @Test
    fun `intelligence allocation crossing 50 reports the milestone`() {
        val registry = registry()
        val agent = registry.register(owner, "Scholar")
        seedAttribute(agent.id, AGENTS.INTELLIGENCE, 49)
        seedUnspent(agent.id, 5)

        val outcome = registry.allocateAttributes(agent.id, mapOf(Attribute.INTELLIGENCE to 2))

        val allocated = assertIs<AllocateAttributesOutcome.Allocated>(outcome)
        assertEquals(listOf(AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 50)), allocated.crossedMilestones)
        assertEquals(51, readAgent(agent.id)[AGENTS.INTELLIGENCE])
    }

    @Test
    fun `single allocation that spans two milestones reports both in order`() {
        val registry = registry()
        val agent = registry.register(owner, "Archmage")
        seedAttribute(agent.id, AGENTS.INTELLIGENCE, 51)
        seedUnspent(agent.id, 200)

        val outcome = registry.allocateAttributes(agent.id, mapOf(Attribute.INTELLIGENCE to 151))

        val allocated = assertIs<AllocateAttributesOutcome.Allocated>(outcome)
        assertEquals(
            listOf(
                AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 100),
                AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 200),
            ),
            allocated.crossedMilestones,
        )
    }

    @Test
    fun `multi-attribute allocation updates every column atomically and recomputes pools once`() {
        val registry = registry()
        val agent = registry.register(owner, "Hybrid")

        val outcome = registry.allocateAttributes(
            agent.id,
            mapOf(
                Attribute.STRENGTH to 2,
                Attribute.CONSTITUTION to 3,
                Attribute.INTELLIGENCE to 0,
            ),
        )

        val allocated = assertIs<AllocateAttributesOutcome.Allocated>(outcome)
        assertEquals(0, allocated.remainingUnspent)
        assertEquals(3, allocated.attributes.strength)
        assertEquals(4, allocated.attributes.constitution)
        assertEquals(1, allocated.attributes.intelligence, "zero delta does not move the value")

        val profile = readProfile(agent.id)
        // CON 4 → maxHp = 50 + 4 × 10 = 90; (CON 4 + DEX 1) × 5 + 30 = 55.
        assertEquals(90, profile[AGENT_PROFILES.MAX_HP])
        assertEquals(55, profile[AGENT_PROFILES.MAX_STAMINA])
    }

    @Test
    fun `requested above unspent is rejected and leaves the row untouched`() {
        val registry = registry()
        val agent = registry.register(owner, "Greedy")

        val outcome = registry.allocateAttributes(agent.id, mapOf(Attribute.STRENGTH to 6))

        val rejection = assertIs<AllocateAttributesOutcome.InsufficientPoints>(outcome)
        assertEquals(5, rejection.unspent)
        assertEquals(6L, rejection.requested)

        val row = readAgent(agent.id)
        assertEquals(1, row[AGENTS.STRENGTH], "no-op on rejection")
        assertEquals(5, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `Int_MAX_VALUE deltas can't smuggle past the unspent guard via overflow`() {
        val registry = registry()
        val agent = registry.register(owner, "Overflow")

        val outcome = registry.allocateAttributes(
            agent.id,
            mapOf(Attribute.STRENGTH to Int.MAX_VALUE, Attribute.DEXTERITY to 1),
        )

        val rejection = assertIs<AllocateAttributesOutcome.InsufficientPoints>(outcome)
        assertEquals(Int.MAX_VALUE.toLong() + 1L, rejection.requested)
        val row = readAgent(agent.id)
        assertEquals(1, row[AGENTS.STRENGTH], "no DB mutation despite the overflow attempt")
    }

    @Test
    fun `negative delta is rejected before any DB call`() {
        val registry = registry()
        val agent = registry.register(owner, "Refunder")

        val outcome = registry.allocateAttributes(
            agent.id,
            mapOf(Attribute.STRENGTH to 2, Attribute.LUCK to -1),
        )

        assertEquals(AllocateAttributesOutcome.NegativeDelta, outcome)
        val row = readAgent(agent.id)
        assertEquals(1, row[AGENTS.STRENGTH])
        assertEquals(5, row[AGENTS.UNSPENT_ATTRIBUTE_POINTS])
    }

    @Test
    fun `unregistered agent returns null`() {
        val registry = registry()
        assertNull(
            registry.allocateAttributes(AgentId(UUID.randomUUID()), mapOf(Attribute.STRENGTH to 1)),
        )
    }

    @Test
    fun `empty deltas map is a no-op success`() {
        val registry = registry()
        val agent = registry.register(owner, "Idle")

        val outcome = registry.allocateAttributes(agent.id, emptyMap())

        val allocated = assertIs<AllocateAttributesOutcome.Allocated>(outcome)
        assertEquals(5, allocated.remainingUnspent)
        assertTrue(allocated.crossedMilestones.isEmpty())
    }

    private fun seedAttribute(id: AgentId, column: org.jooq.TableField<*, Int?>, value: Int) {
        @Suppress("UNCHECKED_CAST")
        dsl.update(AGENTS)
            .set(column as org.jooq.TableField<org.jooq.Record, Int?>, value)
            .where(AGENTS.ID.eq(id.id))
            .execute()
    }

    private fun seedUnspent(id: AgentId, value: Int) {
        dsl.update(AGENTS)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, value)
            .where(AGENTS.ID.eq(id.id))
            .execute()
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
        return JooqAgentRegistry(dsl, UpsertingProfileRepository(dsl), assigner)
    }

    private fun readAgent(id: AgentId) =
        assertNotNull(dsl.selectFrom(AGENTS).where(AGENTS.ID.eq(id.id)).fetchOne())

    private fun readProfile(id: AgentId) =
        assertNotNull(dsl.selectFrom(AGENT_PROFILES).where(AGENT_PROFILES.AGENT_ID.eq(id.id)).fetchOne())

    private class SingleRaceLookup(private val race: Race) : RaceLookup {
        override fun byId(id: RaceId): Race? = if (id == race.id) race else null
        override fun all(): List<Race> = listOf(race)
    }

    private object FixedRandom : RandomSource {
        override fun nextInt(boundExclusive: Int): Int = 0
    }

    private class UpsertingProfileRepository(private val dsl: DSLContext) : AgentProfileRepository {
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
