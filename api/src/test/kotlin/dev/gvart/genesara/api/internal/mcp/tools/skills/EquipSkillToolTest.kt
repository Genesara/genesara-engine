package dev.gvart.genesara.api.internal.mcp.tools.skills

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
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
import kotlin.test.assertTrue

class EquipSkillToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val foraging = SkillId("FORAGING")

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    private val catalog = StubSkillLookup(
        listOf(Skill(foraging, "Foraging", "plants", SkillCategory.GATHERING)),
    )

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns ok and asks the registry to write the slot`() {
        val registry = RecordingRegistry(returns = null)
        val tool = EquipSkillTool(registry, catalog, activity)

        val response = tool.invoke(EquipSkillRequest("FORAGING", slotIndex = 0), toolContext)

        assertEquals("ok", response.kind)
        assertEquals("FORAGING", response.skillId)
        assertEquals(0, response.slotIndex)
        assertEquals(1, registry.setSlotCalls.size)
        val (calledAgent, calledSkill, calledSlot) = registry.setSlotCalls.single()
        assertEquals(agent, calledAgent)
        assertEquals(foraging, calledSkill)
        assertEquals(0, calledSlot)
    }

    @Test
    fun `rejects an unknown skill before touching the registry`() {
        val registry = RecordingRegistry(returns = null)
        val tool = EquipSkillTool(registry, catalog, activity)

        val response = tool.invoke(EquipSkillRequest("PHANTOM", slotIndex = 0), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("unknown_skill", response.reason)
        assertTrue(registry.setSlotCalls.isEmpty(), "registry should not be called for unknown skill")
    }

    @Test
    fun `rejects when the slot index is out of range`() {
        val registry = RecordingRegistry(returns = SkillSlotError.SlotIndexOutOfRange(8, 8))
        val tool = EquipSkillTool(registry, catalog, activity)

        val response = tool.invoke(EquipSkillRequest("FORAGING", slotIndex = 8), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("slot_index_out_of_range", response.reason)
        assertTrue(response.detail?.contains("8") == true)
    }

    @Test
    fun `rejects when the target slot is already occupied`() {
        val registry = RecordingRegistry(returns = SkillSlotError.SlotOccupied(0, SkillId("MINING")))
        val tool = EquipSkillTool(registry, catalog, activity)

        val response = tool.invoke(EquipSkillRequest("FORAGING", slotIndex = 0), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("slot_occupied", response.reason)
        assertTrue(response.detail?.contains("MINING") == true, "detail should name the occupant")
    }

    @Test
    fun `rejects when the skill is already in another slot`() {
        val registry = RecordingRegistry(returns = SkillSlotError.SkillAlreadySlotted(foraging, existingSlotIndex = 3))
        val tool = EquipSkillTool(registry, catalog, activity)

        val response = tool.invoke(EquipSkillRequest("FORAGING", slotIndex = 0), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("skill_already_slotted", response.reason)
        assertTrue(response.detail?.contains("3") == true, "detail should name the existing slot")
    }

    private class RecordingRegistry(private val returns: SkillSlotError?) : AgentSkillsRegistry {
        val setSlotCalls = mutableListOf<Triple<AgentId, SkillId, Int>>()
        override fun snapshot(agent: AgentId) = AgentSkillsSnapshot(emptyMap(), 8, 0)
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
            setSlotCalls += Triple(agent, skill, slotIndex)
            return returns
        }
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
