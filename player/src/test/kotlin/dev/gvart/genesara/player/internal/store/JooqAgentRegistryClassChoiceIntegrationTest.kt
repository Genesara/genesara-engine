package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AssignClassOutcome
import dev.gvart.genesara.player.AssignEvolutionOutcome
import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import dev.gvart.genesara.player.RecordClassOfferOutcome
import dev.gvart.genesara.player.RecordEvolutionOfferOutcome
import dev.gvart.genesara.player.SkillId
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

    // ─────────────────────── L50 evolution flow (#34) ───────────────────────

    private val evoOffer = ClassOffer(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER)

    @Test
    fun `recordPendingEvolutionChoice writes both columns and is reflected on find`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Veteran")
        seedClass(agent.id, AgentClass.SOLDIER)

        val outcome = registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        assertEquals(RecordEvolutionOfferOutcome.Recorded, outcome)
        val row = readAgent(agent.id)
        assertEquals("HEAVY_SOLDIER", row[AGENTS.OFFERED_EVOLUTION_A])
        assertEquals("STEALTH_SOLDIER", row[AGENTS.OFFERED_EVOLUTION_B])
        assertEquals("SOLDIER", row[AGENTS.CLASS_ID])
        val refreshed = registry.find(agent.id)
        assertEquals(evoOffer, refreshed?.offeredEvolutions)
    }

    @Test
    fun `recordPendingEvolutionChoice rejects when the agent has no class`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Pre-class")

        val outcome = registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        assertEquals(RecordEvolutionOfferOutcome.NoClassAssigned, outcome)
        val row = readAgent(agent.id)
        assertNull(row[AGENTS.OFFERED_EVOLUTION_A])
    }

    @Test
    fun `recordPendingEvolutionChoice rejects when the agent is already evolved`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Already")
        seedClass(agent.id, AgentClass.HEAVY_SOLDIER)

        val outcome = registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        val rejection = assertIs<RecordEvolutionOfferOutcome.AlreadyEvolved>(outcome)
        assertEquals(AgentClass.HEAVY_SOLDIER, rejection.existing)
    }

    @Test
    fun `recordPendingEvolutionChoice rejects when an offer is already pending`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Doubler")
        seedClass(agent.id, AgentClass.SOLDIER)
        registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        val outcome = registry.recordPendingEvolutionChoice(
            agent.id,
            ClassOffer(AgentClass.STEALTH_SOLDIER, AgentClass.COMMANDER),
        )

        val rejection = assertIs<RecordEvolutionOfferOutcome.AlreadyOffered>(outcome)
        assertEquals(evoOffer, rejection.existing)
    }

    @Test
    fun `assignEvolution overwrites class_id and clears the offer columns`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Picker")
        seedClass(agent.id, AgentClass.SOLDIER)
        registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        val outcome = registry.assignEvolution(agent.id, AgentClass.STEALTH_SOLDIER)

        val assigned = assertIs<AssignEvolutionOutcome.Assigned>(outcome)
        assertEquals(AgentClass.SOLDIER, assigned.from)
        assertEquals(AgentClass.STEALTH_SOLDIER, assigned.to)
        val row = readAgent(agent.id)
        assertEquals("STEALTH_SOLDIER", row[AGENTS.CLASS_ID])
        assertNull(row[AGENTS.OFFERED_EVOLUTION_A])
        assertNull(row[AGENTS.OFFERED_EVOLUTION_B])
    }

    @Test
    fun `assignEvolution rejects when the chosen class is not in the offer`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Stray")
        seedClass(agent.id, AgentClass.SOLDIER)
        registry.recordPendingEvolutionChoice(agent.id, evoOffer)

        val outcome = registry.assignEvolution(agent.id, AgentClass.COMMANDER)

        val rejection = assertIs<AssignEvolutionOutcome.NotInOffer>(outcome)
        assertEquals(evoOffer, rejection.pending)
        val row = readAgent(agent.id)
        assertEquals("SOLDIER", row[AGENTS.CLASS_ID], "no DB mutation on rejection")
        assertEquals("HEAVY_SOLDIER", row[AGENTS.OFFERED_EVOLUTION_A])
    }

    @Test
    fun `assignEvolution rejects when no offer is pending`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Eager")
        seedClass(agent.id, AgentClass.SOLDIER)

        val outcome = registry.assignEvolution(agent.id, AgentClass.HEAVY_SOLDIER)

        assertEquals(AssignEvolutionOutcome.NoPendingOffer, outcome)
    }

    @Test
    fun `assignEvolution rejects when the agent has no class`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Pre-class")

        val outcome = registry.assignEvolution(agent.id, AgentClass.HEAVY_SOLDIER)

        assertEquals(AssignEvolutionOutcome.NoClassAssigned, outcome)
    }

    @Test
    fun `assignEvolution rejects when the agent is already evolved`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Locked")
        seedClass(agent.id, AgentClass.HEAVY_SOLDIER)

        val outcome = registry.assignEvolution(agent.id, AgentClass.STEALTH_SOLDIER)

        val rejection = assertIs<AssignEvolutionOutcome.AlreadyEvolved>(outcome)
        assertEquals(AgentClass.HEAVY_SOLDIER, rejection.existing)
    }

    @Test
    fun `assignEvolution rejects a cross-tree pick even if it leaks into the offer columns`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "Pivoter")
        seedClass(agent.id, AgentClass.SOLDIER)
        // Hand-tampered offer that bypasses the L50 emitter's catalog scope.
        // The HEAVY_SOLDIER half is legit; SCHOLAR is a Researcher evolution and
        // must not be assignable to a Soldier even though it's in the offer.
        dsl.update(AGENTS)
            .set(AGENTS.OFFERED_EVOLUTION_A, AgentClass.HEAVY_SOLDIER.name)
            .set(AGENTS.OFFERED_EVOLUTION_B, AgentClass.SCHOLAR.name)
            .where(AGENTS.ID.eq(agent.id.id))
            .execute()
        val rogueLookup = LookupWithSoldierAndScholar
        val rogueRegistry = JooqAgentRegistry(dsl, JooqProfileRepository(dsl), assignerStub(), rogueLookup)

        val outcome = rogueRegistry.assignEvolution(agent.id, AgentClass.SCHOLAR)

        val rejection = assertIs<AssignEvolutionOutcome.WrongParent>(outcome)
        assertEquals(AgentClass.SOLDIER, rejection.expectedParent)
        assertEquals(AgentClass.RESEARCHER, rejection.actualParent)
        val row = readAgent(agent.id)
        assertEquals("SOLDIER", row[AGENTS.CLASS_ID], "no DB mutation on cross-tree rejection")
    }

    @Test
    fun `recordPendingEvolutionChoice rejects an offer that includes a non-evolution candidate`() {
        val registry = registryWithSoldierEvolutions()
        val agent = registry.register(owner, "BadOffer")
        seedClass(agent.id, AgentClass.SOLDIER)

        val outcome = registry.recordPendingEvolutionChoice(
            agent.id,
            ClassOffer(AgentClass.HEAVY_SOLDIER, AgentClass.SCOUT),
        )

        val rejection = assertIs<RecordEvolutionOfferOutcome.InvalidCandidate>(outcome)
        assertEquals(AgentClass.SCOUT, rejection.candidate)
        assertEquals(AgentClass.SOLDIER, rejection.expectedParent)
        val row = readAgent(agent.id)
        assertNull(row[AGENTS.OFFERED_EVOLUTION_A], "no DB mutation on rejection")
    }

    private fun assignerStub(): RaceAssigner {
        val race = Race(
            id = RaceId("test_race"),
            displayName = "Test",
            weight = 1,
            attributeMods = AttributeMods.NONE,
            description = "",
        )
        val lookup = SingleRaceLookup(race)
        val props = RaceDefinitionProperties(defaultId = race.id.value)
        return RaceAssigner(lookup, props, FixedRandom)
    }

    /** Catalog where SOLDIER is base + SCHOLAR is a RESEARCHER evolution. */
    private object LookupWithSoldierAndScholar : ClassLookup {
        private val byId: Map<AgentClass, ClassDefinition> = mapOf(
            AgentClass.SOLDIER to baseClass(AgentClass.SOLDIER, listOf(AgentClass.HEAVY_SOLDIER)),
            AgentClass.HEAVY_SOLDIER to evolutionClass(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER),
            AgentClass.RESEARCHER to baseClass(AgentClass.RESEARCHER, listOf(AgentClass.SCHOLAR)),
            AgentClass.SCHOLAR to evolutionClass(AgentClass.SCHOLAR, AgentClass.RESEARCHER),
        )
        override fun byId(classId: AgentClass): ClassDefinition? = byId[classId]
        override fun all(): List<ClassDefinition> = byId.values.toList()
        override fun baseClasses(): List<ClassDefinition> = byId.values.filter { it.parentClass == null }
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> =
            byId[parent]?.evolutions?.mapNotNull { byId[it] } ?: emptyList()
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false

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

    private fun seedClass(agentId: AgentId, classId: AgentClass) {
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, classId.name)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
    }

    private fun registryWithSoldierEvolutions(): JooqAgentRegistry {
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
        return JooqAgentRegistry(dsl, JooqProfileRepository(dsl), assigner, SoldierEvolutionLookup)
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

    /**
     * Catalog stub that knows SOLDIER → {HEAVY_SOLDIER, STEALTH_SOLDIER, COMMANDER}.
     * Mirrors enough of the production catalog for the L50 evolution tests to
     * exercise the parent-class check that gates `recordPendingEvolutionChoice`
     * and `assignEvolution`.
     */
    private object SoldierEvolutionLookup : ClassLookup {
        private val soldier = baseClass(AgentClass.SOLDIER, listOf(
            AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER, AgentClass.COMMANDER,
        ))
        private val evolutions = listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER, AgentClass.COMMANDER)
            .map { evolutionClass(it, AgentClass.SOLDIER) }
        private val byId: Map<AgentClass, ClassDefinition> =
            (listOf(soldier) + evolutions).associateBy { it.id }

        override fun byId(classId: AgentClass): ClassDefinition? = byId[classId]
        override fun all(): List<ClassDefinition> = byId.values.toList()
        override fun baseClasses(): List<ClassDefinition> = listOf(soldier)
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> =
            byId[parent]?.evolutions?.mapNotNull { byId[it] } ?: emptyList()
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false

        private fun baseClass(id: AgentClass, evolutions: List<AgentClass>) = ClassDefinition(
            id = id, displayName = id.name, description = "", sightRange = 3,
            primarySkills = emptySet<SkillId>(), neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(), damageMultipliers = emptyMap(),
            behaviorFingerprint = emptyMap(), parentClass = null, evolutions = evolutions,
        )

        private fun evolutionClass(id: AgentClass, parent: AgentClass) = ClassDefinition(
            id = id, displayName = id.name, description = "", sightRange = 3,
            primarySkills = emptySet<SkillId>(), neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(), damageMultipliers = emptyMap(),
            behaviorFingerprint = emptyMap(), parentClass = parent,
        )
    }
}
