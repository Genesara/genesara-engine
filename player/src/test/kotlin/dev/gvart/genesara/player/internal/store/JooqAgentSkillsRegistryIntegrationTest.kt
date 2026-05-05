package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILLS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILL_RECOMMENDATIONS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILL_SLOTS
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

/**
 * End-to-end test for [JooqAgentSkillsRegistry]: XP grant gated on slotting,
 * recommendation caps + cooldowns, slot insert-only semantics, and the full
 * `AgentSkillsSnapshot` projection.
 */
@Testcontainers
class JooqAgentSkillsRegistryIntegrationTest {

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

    private val foraging = SkillId("FORAGING")
    private val mining = SkillId("MINING")
    private val sword = SkillId("SWORD")
    private val skills = StubSkillLookup(setOf(foraging, mining, sword))

    private lateinit var registry: JooqAgentSkillsRegistry
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_SKILL_RECOMMENDATIONS).cascade().execute()
        dsl.truncate(AGENT_SKILL_SLOTS).cascade().execute()
        dsl.truncate(AGENT_SKILLS).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
        agent = createAgent(level = 1)
        registry = JooqAgentSkillsRegistry(dsl, skills)
    }

    /**
     * Test helper — every `setSlot` call needs the skill to have been recommended at
     * least once, so production-flavoured tests prime the recommendation table here.
     */
    private fun recommend(skill: SkillId, tick: Long = 0L) {
        registry.maybeRecommend(agent, skill, tick)
    }

    @Test
    fun `addXpIfSlotted returns Unslotted and writes nothing when the skill is not in a slot`() {
        val result = registry.addXpIfSlotted(agent, foraging, delta = 50)

        assertEquals(AddXpResult.Unslotted, result)
        val xp = dsl.select(AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .fetchOne(AGENT_SKILLS.XP)
        assertNull(xp, "no row should exist when the skill is unslotted")
    }

    @Test
    fun `addXpIfSlotted writes and accrues when the skill is slotted`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val result = registry.addXpIfSlotted(agent, foraging, delta = 25)
        val accrued = assertIs<AddXpResult.Accrued>(result)
        assertEquals(emptyList(), accrued.crossedMilestones)

        val xp = dsl.select(AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILLS.SKILL_ID.eq(foraging.value))
            .fetchOne(AGENT_SKILLS.XP)
        assertEquals(25, xp)
    }

    @Test
    fun `addXpIfSlotted reports milestones strictly crossed`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val first = assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 50))
        assertEquals(listOf(50), first.crossedMilestones)

        val second = assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 50))
        assertEquals(listOf(100), second.crossedMilestones)

        val third = assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 50))
        assertEquals(listOf(150), third.crossedMilestones)

        val noMore = assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 50))
        assertEquals(emptyList(), noMore.crossedMilestones)
    }

    @Test
    fun `addXpIfSlotted can cross multiple milestones in a single call`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val crossed = assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 120))
        assertEquals(listOf(50, 100), crossed.crossedMilestones)
    }

    @Test
    fun `maybeRecommend fires once for an unslotted skill, then respects cap`() {
        val first = registry.maybeRecommend(agent, foraging, tick = 100)
        assertEquals(1, first)

        // Cooldown gate (~30 ticks) blocks tick 110.
        val cooledDown = registry.maybeRecommend(agent, foraging, tick = 110)
        assertNull(cooledDown)

        // After cooldown, fires again.
        val second = registry.maybeRecommend(agent, foraging, tick = 200)
        assertEquals(2, second)

        val third = registry.maybeRecommend(agent, foraging, tick = 300)
        assertEquals(3, third)

        // Capped at 3 — no further recommendations.
        val capped = registry.maybeRecommend(agent, foraging, tick = 400)
        assertNull(capped)
    }

    @Test
    fun `maybeRecommend returns null once the skill is slotted`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val recommended = registry.maybeRecommend(agent, foraging, tick = 100)
        assertNull(recommended, "slotted skills don't need to be recommended")
    }

    @Test
    fun `maybeRecommend returns null once all slots are full`() {
        // Fill all 8 slots at level 1.
        val fillers = listOf("FORAGING", "MINING", "SWORD")
        // Add more skills to the lookup to fill 8 slots — only 3 in the test catalog,
        // so artificially insert filler rows directly.
        for (i in 0 until 8) {
            dsl.insertInto(AGENT_SKILL_SLOTS)
                .set(AGENT_SKILL_SLOTS.AGENT_ID, agent.id)
                .set(AGENT_SKILL_SLOTS.SLOT_INDEX, i)
                .set(AGENT_SKILL_SLOTS.SKILL_ID, "FILLER_$i")
                .execute()
        }

        // Even an unfilled real skill won't recommend — there's no slot to receive it.
        val recommended = registry.maybeRecommend(agent, foraging, tick = 100)
        assertNull(recommended, "no recommendation when all slots are filled")
    }

    @Test
    fun `setSlot rejects a slot index outside the agent's slot count`() {
        // Level-1 agents have 8 slots (indices 0..7). Index 8 is out of range.
        val err = registry.setSlot(agent, foraging, slotIndex = 8)
        val outOfRange = assertIs<SkillSlotError.SlotIndexOutOfRange>(err)
        assertEquals(8, outOfRange.slotIndex)
        assertEquals(8, outOfRange.slotCount)
    }

    @Test
    fun `setSlot rejects a slot already occupied by another skill — slots are forever`() {
        recommend(foraging)
        recommend(mining)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val err = registry.setSlot(agent, mining, slotIndex = 0)
        val occupied = assertIs<SkillSlotError.SlotOccupied>(err)
        assertEquals(0, occupied.slotIndex)
        assertEquals(foraging, occupied.occupiedBy)
    }

    @Test
    fun `setSlot rejects placing the same skill into a second slot`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        val err = registry.setSlot(agent, foraging, slotIndex = 1)
        val already = assertIs<SkillSlotError.SkillAlreadySlotted>(err)
        assertEquals(0, already.existingSlotIndex)
        assertEquals(foraging, already.skill)
    }

    @Test
    fun `setSlot rejects an undiscovered skill — discovery gate enforces hidden catalog`() {
        // No prior recommendation for foraging → setSlot should reject.
        val err = registry.setSlot(agent, foraging, slotIndex = 0)
        val notDiscovered = assertIs<SkillSlotError.SkillNotDiscovered>(err)
        assertEquals(foraging, notDiscovered.skill)

        // Confirm no slot row was written.
        val occupant = dsl.select(AGENT_SKILL_SLOTS.SKILL_ID)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .fetchOne(AGENT_SKILL_SLOTS.SKILL_ID)
        assertNull(occupant, "rejection must not write a slot row")
    }

    @Test
    fun `setSlot accepts a skill once it has been recommended at least once`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))
    }

    @Test
    fun `snapshot returns only skills the agent has discovered — catalog stays hidden`() {
        // Foraging: recommended, slotted, has XP. Mining: only recommended. Sword:
        // never touched (in catalog, but the agent has done nothing tied to it). The
        // snapshot must include foraging and mining but NOT sword — discovery is the
        // gate.
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))
        registry.addXpIfSlotted(agent, foraging, delta = 35)
        registry.maybeRecommend(agent, mining, tick = 1L)

        val snap = registry.snapshot(agent)
        assertEquals(8, snap.slotCount)
        assertEquals(1, snap.slotsFilled)

        val foragingState = assertNotNull(snap.perSkill[foraging])
        assertEquals(35, foragingState.xp)
        assertEquals(3, foragingState.level) // 35 / 10 = 3
        assertEquals(0, foragingState.slotIndex)
        assertEquals(1, foragingState.recommendCount, "slotting requires a prior recommendation, so foraging's count should be 1")

        val miningState = assertNotNull(snap.perSkill[mining])
        assertEquals(0, miningState.xp)
        assertNull(miningState.slotIndex)
        assertEquals(1, miningState.recommendCount)

        // Sword exists in the catalog but was never touched — must be absent.
        assertNull(snap.perSkill[sword], "undiscovered catalog skills must not appear in the snapshot")
        assertEquals(setOf(foraging, mining), snap.perSkill.keys)
    }

    @Test
    fun `snapshot is empty for a fresh agent`() {
        // No recommendations, no slots, no XP — every catalog skill is undiscovered.
        val snap = registry.snapshot(agent)
        assertEquals(emptyMap(), snap.perSkill)
        assertEquals(8, snap.slotCount)
        assertEquals(0, snap.slotsFilled)
    }

    @Test
    fun `slottedSkillLevel returns 0 for a skill that is not slotted`() {
        assertEquals(0, registry.slottedSkillLevel(agent, foraging))
    }

    @Test
    fun `slottedSkillLevel returns 0 when the skill is slotted but has no XP yet`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))

        assertEquals(0, registry.slottedSkillLevel(agent, foraging))
    }

    @Test
    fun `slottedSkillLevel reflects accrued XP via the floor formula`() {
        recommend(foraging)
        assertNull(registry.setSlot(agent, foraging, slotIndex = 0))
        assertIs<AddXpResult.Accrued>(registry.addXpIfSlotted(agent, foraging, delta = 75))

        assertEquals(7, registry.slottedSkillLevel(agent, foraging))
    }

    private fun createAgent(level: Int): AgentId {
        val id = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, UUID.randomUUID())
            .set(AGENTS.NAME, "test-${id.id.toString().take(6)}")
            .set(AGENTS.RACE_ID, "human_commoner")
            .set(AGENTS.LEVEL, level)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.XP_TO_NEXT, 100)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 5)
            .set(AGENTS.STRENGTH, 5)
            .set(AGENTS.DEXTERITY, 5)
            .set(AGENTS.CONSTITUTION, 5)
            .set(AGENTS.PERCEPTION, 5)
            .set(AGENTS.INTELLIGENCE, 5)
            .set(AGENTS.LUCK, 5)
            .execute()
        return id
    }

    private class StubSkillLookup(ids: Set<SkillId>) : SkillLookup {
        private val byId = ids.associateWith {
            Skill(it, it.value, "stub", SkillCategory.SURVIVAL)
        }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = byId.values.toList()
    }
}
