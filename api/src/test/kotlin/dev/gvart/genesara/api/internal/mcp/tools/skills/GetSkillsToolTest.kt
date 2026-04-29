package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetSkillsToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val foraging = SkillId("FORAGING")
    private val mining = SkillId("MINING")

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    private val catalog = StubSkillLookup(
        listOf(
            Skill(foraging, "Foraging", "plants", SkillCategory.GATHERING),
            Skill(mining, "Mining", "rocks", SkillCategory.GATHERING),
        ),
    )

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns the catalog augmented with per-agent state`() {
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                foraging to AgentSkillState(foraging, xp = 35, level = 3, slotIndex = 0, recommendCount = 0),
                mining to AgentSkillState(mining, xp = 0, level = 0, slotIndex = null, recommendCount = 2),
            ),
            slotCount = 8,
            slotsFilled = 1,
        )
        val tool = GetSkillsTool(StubSkillsRegistry(snapshot), catalog, activity)

        val response = tool.invoke(GetSkillsRequest(), toolContext)

        assertEquals(8, response.slotCount)
        assertEquals(1, response.slotsFilled)
        assertEquals(2, response.skills.size)

        val foragingView = response.skills.first { it.id == "FORAGING" }
        assertEquals("Foraging", foragingView.displayName)
        assertEquals("GATHERING", foragingView.category)
        assertEquals(35, foragingView.xp)
        assertEquals(3, foragingView.level)
        assertEquals(0, foragingView.slotIndex)
        assertEquals(0, foragingView.recommendCount)

        val miningView = response.skills.first { it.id == "MINING" }
        assertEquals(0, miningView.xp)
        assertEquals(0, miningView.level)
        assertNull(miningView.slotIndex)
        assertEquals(2, miningView.recommendCount)
    }

    @Test
    fun `returns zeroed state for catalog skills the agent has never touched`() {
        // Snapshot is empty — the registry returns no per-skill rows. The tool must
        // still surface every catalog skill, with xp/level=0 and slotIndex=null.
        val empty = AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        val tool = GetSkillsTool(StubSkillsRegistry(empty), catalog, activity)

        val response = tool.invoke(GetSkillsRequest(), toolContext)

        assertEquals(2, response.skills.size)
        response.skills.forEach { view ->
            assertEquals(0, view.xp)
            assertEquals(0, view.level)
            assertNull(view.slotIndex)
        }
    }

    private class StubSkillsRegistry(private val snap: AgentSkillsSnapshot) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId) = snap
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class StubSkillLookup(private val skills: List<Skill>) : SkillLookup {
        private val byId = skills.associateBy { it.id }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = skills
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
