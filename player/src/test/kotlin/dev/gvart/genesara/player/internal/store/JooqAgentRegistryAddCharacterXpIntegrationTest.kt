package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
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

@Testcontainers
class JooqAgentRegistryAddCharacterXpIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("xp_it")
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
    fun `partial-bar grant adds xp without leveling`() {
        val registry = registry()
        val agent = registry.register(owner, "Steady")

        val outcome = registry.addCharacterXp(agent.id, 50)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(1, granted.previousLevel)
        assertEquals(1, granted.currentLevel)
        assertEquals(50, granted.xpCurrent)
        assertEquals(100, granted.xpToNext)
        assertEquals(5, granted.unspentAttributePoints)
        assertEquals(false, granted.cappedAtPendingClassChoice)
        val row = readAgent(agent.id)
        assertEquals(50, row[AGENTS.XP_CURRENT])
        assertEquals(1, row[AGENTS.LEVEL])
    }

    @Test
    fun `single-level grant carries surplus xp into the next bar and adds 5 unspent points`() {
        val registry = registry()
        val agent = registry.register(owner, "Climber")

        val outcome = registry.addCharacterXp(agent.id, 130)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        // Cross level 1→2: consume 100 of xp_to_next=100, level becomes 2,
        // new xp_to_next = 200, surplus = 30 stays in the bar.
        assertEquals(2, granted.currentLevel)
        assertEquals(30, granted.xpCurrent)
        assertEquals(200, granted.xpToNext)
        assertEquals(10, granted.unspentAttributePoints, "5 starter + 5 from level-up")
    }

    @Test
    fun `multi-level cascade scales xp_to_next linearly and accumulates unspent`() {
        val registry = registry()
        val agent = registry.register(owner, "Sprinter")

        val outcome = registry.addCharacterXp(agent.id, 100 + 200 + 300 + 50)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        // 1→2 burns 100 (level 2, to_next 200), 2→3 burns 200 (level 3, to_next 300),
        // 3→4 burns 300 (level 4, to_next 400). Surplus 50 stays.
        assertEquals(4, granted.currentLevel)
        assertEquals(50, granted.xpCurrent)
        assertEquals(400, granted.xpToNext)
        assertEquals(20, granted.unspentAttributePoints, "5 starter + 5 × 3 level-ups")
    }

    @Test
    fun `unclassed agent caps at level 10 with full bar even when surplus xp would push further`() {
        val registry = registry()
        val agent = registry.register(owner, "Pretender")
        // Seed at level 9, full bar (xp 0, to_next 900) so a single grant tries to push past 10.
        seed(agent.id, level = 9, xpCurrent = 0, xpToNext = 900)

        val grantTotal = 900 + 1000 + 5000 // overshoots level 10 by a lot
        val outcome = registry.addCharacterXp(agent.id, grantTotal)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(9, granted.previousLevel)
        assertEquals(10, granted.currentLevel)
        assertEquals(1000, granted.xpCurrent, "bar parks at xp_to_next")
        assertEquals(1000, granted.xpToNext)
        assertEquals(true, granted.cappedAtPendingClassChoice)
        val row = readAgent(agent.id)
        assertEquals(10, row[AGENTS.LEVEL])
        assertEquals(1000, row[AGENTS.XP_CURRENT])
    }

    @Test
    fun `base-class agent caps at level 50 with full bar even when surplus xp would push further`() {
        val registry = registry(SoldierEvolutionLookup)
        val agent = registry.register(owner, "Forty-niner")
        seed(agent.id, level = 49, xpCurrent = 0, xpToNext = 4900, classId = AgentClass.SOLDIER)

        val outcome = registry.addCharacterXp(agent.id, 4900 + 5000 + 6000)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(50, granted.currentLevel)
        assertEquals(granted.xpToNext, granted.xpCurrent, "bar capped at full")
        assertEquals(false, granted.cappedAtPendingClassChoice)
        assertEquals(true, granted.cappedAtPendingEvolutionChoice)
    }

    @Test
    fun `evolved agent levels past 50 with no cap`() {
        val registry = registry(SoldierEvolutionLookup)
        val agent = registry.register(owner, "Heavy")
        seed(agent.id, level = 50, xpCurrent = 0, xpToNext = 5000, classId = AgentClass.HEAVY_SOLDIER)

        val outcome = registry.addCharacterXp(agent.id, 5500)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(51, granted.currentLevel)
        assertEquals(500, granted.xpCurrent)
        assertEquals(5100, granted.xpToNext)
        assertEquals(false, granted.cappedAtPendingClassChoice)
        assertEquals(false, granted.cappedAtPendingEvolutionChoice)
    }

    @Test
    fun `classed agent at level 10 levels past the cap`() {
        val registry = registry()
        val agent = registry.register(owner, "Veteran")
        seed(agent.id, level = 10, xpCurrent = 0, xpToNext = 1000, classId = AgentClass.SOLDIER)

        val outcome = registry.addCharacterXp(agent.id, 1100)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(10, granted.previousLevel)
        assertEquals(11, granted.currentLevel)
        assertEquals(100, granted.xpCurrent)
        assertEquals(1100, granted.xpToNext)
        assertEquals(false, granted.cappedAtPendingClassChoice)
    }

    @Test
    fun `negative delta is rejected before any DB call`() {
        val registry = registry()
        val agent = registry.register(owner, "Refunder")

        val outcome = registry.addCharacterXp(agent.id, -10)

        assertEquals(AddCharacterXpOutcome.NegativeDelta, outcome)
        val row = readAgent(agent.id)
        assertEquals(0, row[AGENTS.XP_CURRENT])
        assertEquals(1, row[AGENTS.LEVEL])
    }

    @Test
    fun `zero delta is a no-op success`() {
        val registry = registry()
        val agent = registry.register(owner, "Idle")

        val outcome = registry.addCharacterXp(agent.id, 0)

        val granted = assertIs<AddCharacterXpOutcome.Granted>(outcome)
        assertEquals(1, granted.currentLevel)
        assertEquals(0, granted.xpCurrent)
        assertEquals(false, granted.cappedAtPendingClassChoice)
    }

    @Test
    fun `unregistered agent returns null`() {
        val registry = registry()
        assertNull(registry.addCharacterXp(AgentId(UUID.randomUUID()), 50))
    }

    private fun seed(
        id: AgentId,
        level: Int,
        xpCurrent: Int,
        xpToNext: Int,
        classId: AgentClass? = null,
    ) {
        val update = dsl.update(AGENTS)
            .set(AGENTS.LEVEL, level)
            .set(AGENTS.XP_CURRENT, xpCurrent)
            .set(AGENTS.XP_TO_NEXT, xpToNext)
        if (classId != null) update.set(AGENTS.CLASS_ID, classId.name)
        update.where(AGENTS.ID.eq(id.id)).execute()
    }

    private fun registry(classes: ClassLookup = NoOpClassLookup): JooqAgentRegistry {
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
        return JooqAgentRegistry(dsl, JooqProfileRepository(dsl), assigner, classes)
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

    /** Catalog stub: SOLDIER is a base class with 3 evolutions; the rest are evolutions. */
    private object SoldierEvolutionLookup : ClassLookup {
        private val byId: Map<AgentClass, ClassDefinition> = mapOf(
            AgentClass.SOLDIER to baseClass(AgentClass.SOLDIER, listOf(
                AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER, AgentClass.COMMANDER,
            )),
            AgentClass.HEAVY_SOLDIER to evolutionClass(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER),
            AgentClass.STEALTH_SOLDIER to evolutionClass(AgentClass.STEALTH_SOLDIER, AgentClass.SOLDIER),
            AgentClass.COMMANDER to evolutionClass(AgentClass.COMMANDER, AgentClass.SOLDIER),
        )

        override fun byId(classId: AgentClass): ClassDefinition? = byId[classId]
        override fun all(): List<ClassDefinition> = byId.values.toList()
        override fun baseClasses(): List<ClassDefinition> = listOf(byId[AgentClass.SOLDIER]!!)
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> =
            byId[parent]?.evolutions?.mapNotNull { byId[it] } ?: emptyList()
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: dev.gvart.genesara.player.SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: dev.gvart.genesara.player.SkillId): Boolean = false

        private fun baseClass(id: AgentClass, evolutions: List<AgentClass>) = ClassDefinition(
            id = id, displayName = id.name, description = "", sightRange = 3,
            primarySkills = emptySet(), neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(), damageMultipliers = emptyMap(),
            behaviorFingerprint = emptyMap(), parentClass = null, evolutions = evolutions,
        )

        private fun evolutionClass(id: AgentClass, parent: AgentClass) = ClassDefinition(
            id = id, displayName = id.name, description = "", sightRange = 3,
            primarySkills = emptySet(), neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(), damageMultipliers = emptyMap(),
            behaviorFingerprint = emptyMap(), parentClass = parent,
        )
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
