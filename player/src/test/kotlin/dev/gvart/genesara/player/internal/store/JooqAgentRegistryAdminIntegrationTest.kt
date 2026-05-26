package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AdminAttributeOverrides
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.ClassOffer
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
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentRegistryAdminIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("admin_it")
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
    fun `adminSetLevel writes level, recomputes xpToNext, clamps xpCurrent`() {
        val registry = registry()
        val agent = registry.register(owner, "Levelable")
        dsl.update(AGENTS)
            .set(AGENTS.XP_CURRENT, 9_999)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val updated = assertNotNull(registry.adminSetLevel(agent.id, 7))

        assertEquals(7, updated.level)
        assertEquals(700, updated.xpToNext)
        assertEquals(700, updated.xpCurrent)
    }

    @Test
    fun `adminSetLevel returns null for an unknown agent`() {
        val registry = registry()
        assertNull(registry.adminSetLevel(AgentId(UUID.randomUUID()), 5))
    }

    @Test
    fun `adminSetAttributes writes overrides and recomputes derived pools`() {
        val registry = registry()
        val agent = registry.register(owner, "Statter")

        val updated = assertNotNull(
            registry.adminSetAttributes(
                agent.id,
                AdminAttributeOverrides(strength = 12, constitution = 8, unspent = 4),
            ),
        )

        assertEquals(12, updated.attributes.strength)
        assertEquals(8, updated.attributes.constitution)
        assertEquals(4, updated.unspentAttributePoints)
        val profile = readProfile(agent.id)
        assertTrue(profile[AGENT_PROFILES.MAX_HP]!! > 0)
    }

    @Test
    fun `adminSetAttributes leaves untouched columns alone`() {
        val registry = registry()
        val agent = registry.register(owner, "Partial")
        val beforeDex = agent.attributes.dexterity

        val updated = assertNotNull(registry.adminSetAttributes(agent.id, AdminAttributeOverrides(strength = 20)))

        assertEquals(20, updated.attributes.strength)
        assertEquals(beforeDex, updated.attributes.dexterity)
    }

    @Test
    fun `adminAssignClass writes class and clears the pending class offer`() {
        val registry = registry()
        val agent = registry.register(owner, "Pickable")
        registry.recordPendingClassChoice(agent.id, ClassOffer(AgentClass.SOLDIER, AgentClass.SCOUT))

        val updated = assertNotNull(registry.adminAssignClass(agent.id, AgentClass.RESEARCHER))

        assertEquals(AgentClass.RESEARCHER, updated.classId)
        assertNull(updated.offeredClasses)
    }

    @Test
    fun `adminAssignClass overrides an already-classed agent`() {
        val registry = registry()
        val agent = registry.register(owner, "Reclassable")
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, AgentClass.SOLDIER.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val updated = assertNotNull(registry.adminAssignClass(agent.id, AgentClass.HUNTER))

        assertEquals(AgentClass.HUNTER, updated.classId)
    }

    @Test
    fun `adminClearPendingOffers clears offer columns but keeps class_id`() {
        val registry = registry()
        val agent = registry.register(owner, "Stuck")
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, AgentClass.SOLDIER.name)
            .set(AGENTS.OFFERED_EVOLUTION_A, AgentClass.HEAVY_SOLDIER.name)
            .set(AGENTS.OFFERED_EVOLUTION_B, AgentClass.STEALTH_SOLDIER.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val updated = assertNotNull(registry.adminClearPendingOffers(agent.id))

        assertEquals(AgentClass.SOLDIER, updated.classId)
        assertNull(updated.offeredClasses)
        assertNull(updated.offeredEvolutions)
    }

    @Test
    fun `adminClearClassAndOffers clears class and both offer pairs`() {
        val registry = registry()
        val agent = registry.register(owner, "Reset")
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, AgentClass.SOLDIER.name)
            .set(AGENTS.OFFERED_EVOLUTION_A, AgentClass.HEAVY_SOLDIER.name)
            .set(AGENTS.OFFERED_EVOLUTION_B, AgentClass.STEALTH_SOLDIER.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()

        val updated = assertNotNull(registry.adminClearClassAndOffers(agent.id))

        assertNull(updated.classId)
        assertNull(updated.offeredClasses)
        assertNull(updated.offeredEvolutions)
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

    private fun readProfile(id: AgentId) =
        assertNotNull(dsl.selectFrom(AGENT_PROFILES).where(AGENT_PROFILES.AGENT_ID.eq(id.id)).fetchOne())

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
                .onConflict(AGENT_PROFILES.AGENT_ID)
                .doUpdate()
                .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
                .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
                .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
                .execute()
        }
    }
}
