package dev.gvart.genesara.api.internal.mcp.tools.perks

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectPerkToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val sword = SkillId("SWORD")
    private val bleeder = perk("SWORD_BLEEDER", sword, 50)
    private val sharpen = perk("SWORD_SHARPEN_EDGE", sword, 50)
    private val swordRiposte = perk("SWORD_RIPOSTE", sword, 100)
    private val perks = StubPerkLookup(listOf(bleeder, sharpen, swordRiposte))

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val tickClock = StubTickClock(currentTick = 7L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `records the chosen perk and emits PerkChosen at the current tick`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 60))
        val perksRegistry = RecordingPerksRegistry()
        val publisher = RecordingPublisher()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, publisher, activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val response = tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals("ok", response.kind)
        assertEquals(Triple(agent, bleeder.id, 7L), perksRegistry.recordCalls.single())
        val event = publisher.events.filterIsInstance<AgentEvent.PerkChosen>().single()
        assertEquals(agent, event.agent)
        assertEquals(sword, event.skill)
        assertEquals(50, event.milestone)
        assertEquals(bleeder.id, event.perk)
        assertEquals(7L, event.tick)
    }

    @Test
    fun `successful perk pick invokes recipeLearning with the perk and current tick`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 60))
        val perksRegistry = RecordingPerksRegistry()
        val recipeLearning = RecordingRecipeLearning()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, RecordingPublisher(), activity, recipeLearning)

        tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals(listOf(agent to bleeder.id to 7L), recipeLearning.perkCalls)
    }

    @Test
    fun `recipeLearning is not invoked when the perk record was rejected`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 60))
        val perksRegistry = RecordingPerksRegistry(
            recordResult = RecordPerkResult.MilestoneAlreadyChosen(
                skill = sword,
                milestoneLevel = 50,
                existing = sharpen.id,
            ),
        )
        val recipeLearning = RecordingRecipeLearning()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, RecordingPublisher(), activity, recipeLearning)

        tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertTrue(recipeLearning.perkCalls.isEmpty())
    }

    @Test
    fun `rejects an unknown perk before touching the registry`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 60))
        val perksRegistry = RecordingPerksRegistry()
        val publisher = RecordingPublisher()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, publisher, activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val response = tool.invoke(skillId = "SWORD", milestone = 50, perkId = "PHANTOM", toolContext = toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("unknown_perk", response.reason)
        assertTrue(perksRegistry.recordCalls.isEmpty())
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `rejects when the perk does not match the requested skill or milestone`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 100))
        val tool = SelectPerkTool(perks, RecordingPerksRegistry(), skills, tickClock, RecordingPublisher(), activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val mismatchSkill = tool.invoke(skillId = "BOW", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)
        val mismatchMilestone = tool.invoke(skillId = "SWORD", milestone = 100, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals("perk_mismatch", mismatchSkill.reason)
        assertEquals("perk_mismatch", mismatchMilestone.reason)
    }

    @Test
    fun `rejects when the agent's slotted skill level has not reached the milestone`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 49))
        val perksRegistry = RecordingPerksRegistry()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, RecordingPublisher(), activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val response = tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals("milestone_not_reached", response.reason)
        assertTrue(response.detail?.contains("49") == true)
        assertTrue(perksRegistry.recordCalls.isEmpty())
    }

    @Test
    fun `rejects when the skill is not slotted at all`() {
        val skills = StubSkillsRegistry(slottedLevels = emptyMap())
        val tool = SelectPerkTool(perks, RecordingPerksRegistry(), skills, tickClock, RecordingPublisher(), activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val response = tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals("milestone_not_reached", response.reason)
    }

    @Test
    fun `rejects re-pick at the same milestone naming the existing perk`() {
        val skills = StubSkillsRegistry(slottedLevels = mapOf(sword to 60))
        val perksRegistry = RecordingPerksRegistry(
            recordResult = RecordPerkResult.MilestoneAlreadyChosen(
                skill = sword,
                milestoneLevel = 50,
                existing = sharpen.id,
            ),
        )
        val publisher = RecordingPublisher()
        val tool = SelectPerkTool(perks, perksRegistry, skills, tickClock, publisher, activity, dev.gvart.genesara.world.RecipeLearning.NoOp)

        val response = tool.invoke(skillId = "SWORD", milestone = 50, perkId = "SWORD_BLEEDER", toolContext = toolContext)

        assertEquals("already_chosen", response.reason)
        assertTrue(response.detail?.contains("SWORD_SHARPEN_EDGE") == true)
        assertTrue(publisher.events.none { it is AgentEvent.PerkChosen })
    }

    private fun perk(id: String, skill: SkillId, milestone: Int) = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestone,
        displayName = id,
        description = id,
        effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 1),
    )

    private class RecordingPerksRegistry(
        private val recordResult: RecordPerkResult = RecordPerkResult.Recorded,
    ) : AgentPerksRegistry {
        val recordCalls = mutableListOf<Triple<AgentId, PerkId, Long>>()
        override fun snapshot(agent: AgentId): AgentPerksSnapshot = AgentPerksSnapshot(emptyList())
        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult {
            recordCalls += Triple(agent, perk, tick)
            return recordResult
        }
    }

    private class StubSkillsRegistry(private val slottedLevels: Map<SkillId, Int>) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId) = AgentSkillsSnapshot(emptyMap(), 8, 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int = slottedLevels[skill] ?: 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class StubPerkLookup(private val perks: List<Perk>) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? {
            val opts = perks.filter { it.skill == skill && it.milestoneLevel == milestoneLevel }
            return if (opts.isEmpty()) null else PerkChoice(skill, milestoneLevel, opts)
        }
        override fun choicesFor(skill: SkillId): List<PerkChoice> =
            perks.filter { it.skill == skill }
                .groupBy { it.milestoneLevel }
                .toSortedMap()
                .map { (level, list) -> PerkChoice(skill, level, list) }
        override fun all(): List<Perk> = perks
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class RecordingRecipeLearning : dev.gvart.genesara.world.RecipeLearning {
        val perkCalls = mutableListOf<Pair<Pair<AgentId, PerkId>, Long>>()
        override fun learnFromPerk(agent: AgentId, perk: PerkId, tick: Long): List<dev.gvart.genesara.world.RecipeId> {
            perkCalls += (agent to perk) to tick
            return emptyList()
        }
        override fun learnFromItem(agent: AgentId, item: dev.gvart.genesara.world.ItemId, tick: Long) =
            emptyList<dev.gvart.genesara.world.RecipeId>()
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
